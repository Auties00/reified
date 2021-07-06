package it.auties.reified.simplified;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import it.auties.reified.scanner.VariableScanner;
import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.util.Optional;

import static com.sun.tools.javac.code.Kinds.Kind.PCK;
import static com.sun.tools.javac.code.Kinds.Kind.TYP;

@AllArgsConstructor
public class SimpleMethods {
    private final SimpleTypes simpleTypes;
    private final Names names;
    private final Enter enter;
    private final Attr attr;

    public Optional<Symbol.MethodSymbol> resolveMethod(@NonNull JCTree.JCClassDecl enclosingClass, @NonNull JCTree.JCMethodInvocation invocation) {
        var invoked = invocation.getMethodSelect();
        if (TreeInfo.isSuperCall(invoked)) {
            return Optional.empty();
        }

        var classSymbol = enclosingClass.sym;
        if (invoked instanceof JCTree.JCIdent) {
            var methodName = ((JCTree.JCIdent) invoked).getName();
            var methodSymbol = (Symbol.MethodSymbol) classSymbol.members().findFirst(methodName);
            return Optional.ofNullable(methodSymbol);
        }

        if (invoked instanceof JCTree.JCFieldAccess) {
            var access = (JCTree.JCFieldAccess) invoked;
            var resolvedClassSymbol = resolveClassSymbol(access, classSymbol, enclosingClass);
            return resolveMethodSymbol(resolvedClassSymbol, access);
        }

        throw new IllegalArgumentException("Cannot resolve method, unknown method selection type: " + invoked.getClass().getName());
    }

    private Symbol resolveClassSymbol(JCTree.JCFieldAccess access, Symbol.ClassSymbol classSymbol, JCTree.JCClassDecl enclosingClass) {
        var selected = access.selected;
        if (selected instanceof JCTree.JCIdent) {
            var identity = (JCTree.JCIdent) selected;
            if (identity.getName().equals(names._super)) {
                var superClass = classSymbol.getSuperclass();
                return superClass.asElement();
            }

            if (identity.getName().equals(names._this)) {
                return classSymbol;
            }

            return resolveClassSymbol(identity, enclosingClass, classSymbol.asType().asElement());
        }

        if (selected instanceof JCTree.JCNewClass) {
            var initialization = (JCTree.JCNewClass) selected;
            return resolveClassSymbol(initialization, classSymbol.asType().asElement());
        }

        if (selected instanceof JCTree.JCFieldAccess) {
            var innerAccess = (JCTree.JCFieldAccess) selected;
            return resolveClassSymbol(innerAccess, classSymbol, enclosingClass);
        }

        if (selected instanceof JCTree.JCMethodInvocation) {
            var invocation = (JCTree.JCMethodInvocation) selected;
            var invoked = resolveMethod(enclosingClass, invocation).orElseThrow();
            return invoked.getReturnType().asElement();
        }

        throw new IllegalArgumentException("Cannot resolve class symbol: " + selected.getClass().getName());
    }

    private Symbol resolveClassSymbol(JCTree.JCIdent identity, JCTree.JCClassDecl enclosingClass, Symbol.TypeSymbol typeSymbol) {
        var classEnv = enter.getClassEnv(typeSymbol);
        var identitySymbol = attr.attribType(identity, classEnv).asElement();
        if (identitySymbol.kind == TYP || identitySymbol.kind == PCK) {
            var classType = attr.attribType(identity, classEnv);
            Assert.check(!classType.isErroneous(), String.format("Erroneous type: %s", classType));
            return classType.asElement();
        }

        var variableDeclaration = new VariableScanner(identity).scan(enclosingClass);
        if (variableDeclaration.sym != null) {
            return variableDeclaration.sym.asType().asElement();
        }

        var variableType = variableDeclaration.vartype;
        if (variableType != null) {
            return attr.attribType(variableType, classEnv).asElement();
        }

        return simpleTypes.resolveImplicitType(variableDeclaration, classEnv).asElement();
    }

    private Symbol resolveClassSymbol(JCTree.JCNewClass init, Symbol.TypeSymbol typeSymbol) {
        var classEnv = enter.getClassEnv(typeSymbol);
        var clazz = init.clazz;
        if (clazz instanceof JCTree.JCIdent) {
            var identitySymbol = attr.attribType(clazz, classEnv).asElement();
            return Assert.checkNonNull(identitySymbol);
        }

        if (clazz instanceof JCTree.JCTypeApply) {
            var typeApply = (JCTree.JCTypeApply) clazz;
            var typeApplySymbol = attr.attribType(typeApply.clazz, classEnv).asElement();
            return Assert.checkNonNull(typeApplySymbol);
        }

        throw new IllegalArgumentException("Cannot resolve class symbol from class initialization: " + init.getClass().getName());
    }

    private Optional<Symbol.MethodSymbol> resolveMethodSymbol(Symbol symbol, JCTree.JCFieldAccess access) {
        var result = symbol.members().findFirst(access.name);
        if (result instanceof Symbol.MethodSymbol) {
            return Optional.of((Symbol.MethodSymbol) result);
        }

        if (symbol instanceof Symbol.ClassSymbol) {
            var classSymbol = (Symbol.ClassSymbol) symbol;
            var superClass = classSymbol.getSuperclass().asElement();
            if (superClass instanceof Symbol.ClassSymbol) {
                return resolveMethodSymbol(superClass, access);
            }
        }

        return Optional.empty();
    }

    public Type resolveMethodType(Symbol.TypeVariableSymbol typeVariable, JCTree.JCMethodInvocation invocation, Symbol.MethodSymbol invoked, Env<AttrContext> env) {
        var args = invocation.getTypeArguments();
        if (args != null && !args.isEmpty()) {
            return simpleTypes.resolveFirstGenericType(typeVariable, args, env)
                    .map(Symbol::asType)
                    .orElseThrow(() -> new IllegalArgumentException("Cannot deduce type from generic method call with explicit type"));
        }

        var returnType = invoked.getReturnType();
        if(typeVariable.asType().equals(returnType)){
            return simpleTypes.resolveExpressionType(invocation, env)
                    .orElseThrow(() -> new IllegalArgumentException("Cannot deduce type from generic method call with matching return type"));
        }

        var parametrizedArgs = matchTypeParamToTypedArg(invocation, invoked, env, typeVariable);
        return simpleTypes.commonType(parametrizedArgs).orElse(simpleTypes.erase(typeVariable));
    }

    private List<Type> matchTypeParamToTypedArg(JCTree.JCMethodInvocation invocation, Symbol.MethodSymbol invoked, Env<AttrContext> env, Symbol.TypeVariableSymbol paramSymbol) {
        return invoked.getParameters()
                .stream()
                .filter(candidate -> paramSymbol.asType().equals(candidate.asType()))
                .map(invoked.getParameters()::indexOf)
                .map(index -> invocation.getArguments().get(index))
                .map(candidate -> simpleTypes.resolveExpressionType(candidate, env))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(simpleTypes::boxed)
                .collect(List.collector());
    }
}
