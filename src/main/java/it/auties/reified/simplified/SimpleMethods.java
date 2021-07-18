package it.auties.reified.simplified;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.List;
import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

@AllArgsConstructor
public class SimpleMethods {
    private final SimpleTypes simpleTypes;

    public Symbol.MethodSymbol resolveMethod(@NonNull JCTree.JCClassDecl enclosingClass, JCTree.JCMethodDecl enclosingMethod, @NonNull JCTree.JCMethodInvocation invocation) {
        var classEnv = simpleTypes.findClassEnv(enclosingClass);
        simpleTypes.resolveEnv(classEnv);
        var symbol = TreeInfo.symbol(invocation.getMethodSelect());
        return (Symbol.MethodSymbol) symbol;
    }

    public Type resolveMethodType(Symbol.TypeVariableSymbol typeVariable, JCTree.JCMethodInvocation invocation, Symbol.MethodSymbol invoked, JCTree.JCClassDecl enclosingClass, JCTree.JCMethodDecl enclosingMethod, JCTree.JCStatement enclosingStatement) {
        var args = invocation.getTypeArguments();
        if (args != null && !args.isEmpty()) {
            var deduced = simpleTypes.matchTypeParamToTypedArg(typeVariable, invoked.getTypeParameters(), args, enclosingClass);
            if(deduced.isEmpty()){
                throw new IllegalArgumentException("Cannot resolve method type for explicit type variable");
            }

            return deduced.head;
        }

        var returnType = invoked.getReturnType();
        var parameterType = resolveMethodType(typeVariable, invocation, invoked, enclosingClass);
        var flatReturnType = simpleTypes.flattenGenericType(returnType).iterator();
        var statementType = resolveMethodType(typeVariable, flatReturnType, enclosingClass, enclosingMethod, enclosingStatement);
        return statementType.map(type -> resolveMethodType(typeVariable, parameterType, type))
                .orElse(resolveMethodType(typeVariable, parameterType));
    }

    private Type resolveMethodType(Symbol.TypeVariableSymbol typeVariable, Type parameterType, Type type) {
        if (simpleTypes.isNotWildCard(type)) {
            return type;
        }

        return resolveMethodType(typeVariable, parameterType);
    }

    private Type resolveMethodType(Symbol.TypeVariableSymbol typeVariable, Type parameterType) {
        return Objects.requireNonNullElse(parameterType, simpleTypes.erase(typeVariable));
    }

    private Type resolveMethodType(Symbol.TypeVariableSymbol typeVariable, JCTree.JCMethodInvocation invocation, Symbol.MethodSymbol invoked, JCTree.JCClassDecl enclosingClass) {
        var invocationArgs = simpleTypes.resolveTypes(invocation.getArguments(), enclosingClass);
        var constructorParams = invoked.getParameters();
        var commonTypes = simpleTypes.matchTypeVariableSymbolToArgs(typeVariable, List.from(constructorParams), invocationArgs);
        return simpleTypes.commonType(commonTypes);
    }

    private Optional<Type> resolveMethodType(Symbol.TypeVariableSymbol typeVariable, Iterator<Type> flatReturnType, JCTree.JCClassDecl enclosingClass, JCTree.JCMethodDecl enclosingMethod, JCTree.JCStatement enclosingStatement) {
        if (enclosingStatement instanceof JCTree.JCReturn) {
            var env = simpleTypes.findClassEnv(enclosingClass);
            var methodReturnType = simpleTypes.resolveClassType(enclosingMethod.getReturnType(), env);
            if (methodReturnType.isEmpty()) {
                return Optional.empty();
            }

            var flatMethodReturn = simpleTypes.flattenGenericType(methodReturnType.get()).iterator();
            return simpleTypes.resolveImplicitType(flatMethodReturn, flatReturnType, typeVariable);
        }

        if (enclosingStatement instanceof JCTree.JCVariableDecl) {
            var variable = (JCTree.JCVariableDecl) enclosingStatement;
            if (variable.isImplicitlyTyped()) {
                return Optional.empty();
            }

            var env = simpleTypes.findClassEnv(enclosingClass);
            var variableType = simpleTypes.resolveClassType(variable.vartype, env);
            if (variableType.isEmpty()) {
                return Optional.empty();
            }

            var flatVariableType = simpleTypes.flattenGenericType(variableType.get()).iterator();
            return simpleTypes.resolveImplicitType(flatVariableType, flatReturnType, typeVariable);
        }

        return Optional.empty();
    }
}
