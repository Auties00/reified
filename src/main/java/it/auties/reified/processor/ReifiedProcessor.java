package it.auties.reified.processor;

import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import it.auties.reified.annotation.Reified;
import it.auties.reified.model.ReifiedCall;
import it.auties.reified.model.ReifiedCandidate;
import it.auties.reified.model.ReifiedDeclaration;
import it.auties.reified.scanner.ClassInitializationScanner;
import it.auties.reified.scanner.ExtendedClassesScanner;
import it.auties.reified.scanner.MethodInvocationScanner;
import it.auties.reified.simplified.*;
import it.auties.reified.util.StreamUtils;
import lombok.experimental.ExtensionMethod;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedAnnotationTypes(Reified.PATH)
@SupportedSourceVersion(SourceVersion.RELEASE_11)
@ExtensionMethod(StreamUtils.class)
public class ReifiedProcessor extends AbstractProcessor {
    private SimpleTypes simpleTypes;
    private SimpleClasses simpleClasses;
    private SimpleMethods simpleMethods;
    private SimpleMaker simpleMaker;
    private Trees trees;
    private RoundEnvironment environment;
    private List<ReifiedDeclaration> reifiedDeclarations;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try{
            init(roundEnv);
            lookup();
            processing();
            return true;
        }catch (Throwable ex){
            ex.printStackTrace();
            return false;
        }
    }

    private void init(RoundEnvironment environment) {
        var context = SimpleContext.resolveContext(processingEnv);
        var enter = Enter.instance(context);
        var attr = Attr.instance(context);
        var types = Types.instance(context);
        var treeMaker = TreeMaker.instance(context);
        var memberEnter = MemberEnter.instance(context);

        this.trees = JavacTrees.instance(context);
        this.simpleTypes = new SimpleTypes(processingEnv, types, attr, enter, memberEnter);
        this.simpleClasses = new SimpleClasses(simpleTypes);
        this.simpleMethods = new SimpleMethods(simpleTypes);
        this.simpleMaker = new SimpleMaker(simpleTypes, treeMaker);
        this.environment = environment;
    }

    private void lookup() {
        var candidates = findAnnotatedTrees();
        this.reifiedDeclarations = parseCandidates(candidates);
    }

    private List<ReifiedCandidate> findAnnotatedTrees() {
        return environment.getElementsAnnotatedWith(Reified.class)
                .stream()
                .map(this::findAnnotatedTree)
                .collect(List.collector())
                .reverse();
    }

    private ReifiedCandidate findAnnotatedTree(Element element) {
        var typeVariable = (Symbol.TypeVariableSymbol) element;
        var owner = typeVariable.getEnclosingElement();
        if(owner instanceof Symbol.ClassSymbol){
            var classSymbol = (Symbol.ClassSymbol) owner;
            var env = simpleTypes.findClassEnv((Type.ClassType) classSymbol.asType());
            return new ReifiedCandidate(typeVariable, (JCTree.JCClassDecl) env.tree, null);
        }

        if(owner instanceof Symbol.MethodSymbol){
            var methodSymbol = (Symbol.MethodSymbol) owner;
            var classSymbol = (Symbol.ClassSymbol) methodSymbol.getEnclosingElement();
            var classEnv = simpleTypes.findClassEnv((Type.ClassType) classSymbol.asType());
            var methodEnv = trees.getPath(methodSymbol);
            return new ReifiedCandidate(typeVariable, (JCTree.JCClassDecl) classEnv.tree, (JCTree.JCMethodDecl) methodEnv.getLeaf());
        }

        throw new IllegalArgumentException("Cannot find annotated tree, unknown owner: " + owner.getClass().getName());
    }

    private List<ReifiedDeclaration> parseCandidates(List<ReifiedCandidate> candidates) {
        return candidates.stream()
                .map(this::parseCandidate)
                .collect(List.collector());
    }

    private ReifiedDeclaration parseCandidate(ReifiedCandidate candidate) {
        return ReifiedDeclaration.builder()
                .typeParameter(candidate.typeVariableSymbol())
                .enclosingClass(candidate.enclosingClass())
                .methods(findMembers(candidate))
                .isClass(isClassScoped(candidate))
                .modifier(simpleClasses.findRealAccess(candidate.enclosingClass(), candidate.enclosingMethod()))
                .build();
    }

    private boolean isClassScoped(ReifiedCandidate candidate){
        return candidate.enclosingMethod() == null;
    }

    private List<JCTree.JCMethodDecl> findMembers(ReifiedCandidate candidate) {
        if(isClassScoped(candidate)){
            return simpleClasses.findConstructors(candidate.enclosingClass());
        }

        return List.of(candidate.enclosingMethod());
    }

    private void processing() {
        reifiedDeclarations.forEach(this::processTypeParameter);
        reifiedDeclarations.forEach(simpleMaker::processMembers);
    }

    private void processChildClass(ReifiedDeclaration reifiedDeclaration) {
        var enclosingClass = reifiedDeclaration.enclosingClass();
        var childClasses = findChildClasses(enclosingClass);
        childClasses.forEach(childClass -> processChildClass(reifiedDeclaration, enclosingClass, childClass));
    }

    private void processChildClass(ReifiedDeclaration reifiedDeclaration, JCTree.JCClassDecl enclosingClass, JCTree.JCClassDecl childClass) {
        var type = findChildClassType(reifiedDeclaration, enclosingClass, childClass);
        var literal = createClassLiteral(type, childClass, null);
        addSuperParam(childClass, literal);
    }

    private void addSuperParam(JCTree.JCClassDecl childClass, JCTree.JCExpression literal) {
        simpleClasses.findConstructors(childClass)
                .stream()
                .map(constructor -> constructor.body.stats)
                .filter(stats -> !stats.isEmpty())
                .map(stats -> stats.head)
                .filter(TreeInfo::isSuperCall)
                .forEach(superCall -> simpleMaker.addSuperParam(literal, (JCTree.JCExpressionStatement) superCall));
    }

    private Type findChildClassType(ReifiedDeclaration reifiedDeclaration, JCTree.JCClassDecl enclosingClass, JCTree.JCClassDecl childClass) {
        var extendClause = childClass.getExtendsClause();
        switch (extendClause.getTag()){
            case IDENT:
                return simpleTypes.erase(reifiedDeclaration.typeParameter());
            case TYPEAPPLY:
                var typeApply = (JCTree.JCTypeApply) extendClause;
                var types = simpleTypes.matchTypeParamToTypedArg(reifiedDeclaration.typeParameter(), enclosingClass.sym.getTypeParameters(), typeApply.getTypeArguments(), childClass);
                return Objects.requireNonNullElse(simpleTypes.commonType(types), simpleTypes.erase(reifiedDeclaration.typeParameter()));
            default:
                throw new IllegalArgumentException("Unsupported tag for child class type: " + extendClause.getTag().name());
        }
    }

    private List<JCTree.JCClassDecl> findChildClasses(JCTree.JCClassDecl superClass) {
        var scanner = new ExtendedClassesScanner(superClass, simpleTypes);
        return environment.getRootElements()
                .stream()
                .map(trees::getTree)
                .map(tree -> (JCTree.JCClassDecl) tree)
                .map(scanner::scan)
                .flatMap(Collection::stream)
                .collect(List.collector());
    }


    private void processTypeParameter(ReifiedDeclaration reifiedDeclaration) {
        if (reifiedDeclaration.isClass()) {
            processClassParameter(reifiedDeclaration);
            processChildClass(reifiedDeclaration);
            return;
        }

       processMethodParameter(reifiedDeclaration);
    }

    private void processMethodParameter(ReifiedDeclaration reifiedDeclaration) {
        var methodScanner = new MethodInvocationScanner(reifiedDeclaration, simpleClasses, simpleMethods);
        findCompilationUnits(reifiedDeclaration)
                .stream()
                .map(methodScanner::scan)
                .flatMap(Collection::stream)
                .forEach(methodInvocation -> processMethodParameter(reifiedDeclaration.typeParameter(), methodInvocation));
    }

    private void processMethodParameter(Symbol.TypeVariableSymbol typeVariable, ReifiedCall inv) {
        var invocation = (JCTree.JCMethodInvocation) inv.invocation();
        var clazz = inv.enclosingClass();
        var method = inv.enclosingMethod();

        var type = simpleTypes.resolveWildCard(simpleMethods.resolveMethodType(
                typeVariable,
                invocation,
                inv.invoked(),
                clazz,
                method,
                inv.enclosingStatement()
        ));

        invocation.args = invocation.args.prepend(createClassLiteral(type, clazz, method));
    }

    private void processClassParameter(ReifiedDeclaration reifiedDeclaration) {
        var classScanner = new ClassInitializationScanner(reifiedDeclaration, simpleClasses, simpleMethods);
        findCompilationUnits(reifiedDeclaration)
                .stream()
                .map(classScanner::scan)
                .flatMap(Collection::stream)
                .forEach(classInit -> processClassParameter(reifiedDeclaration.typeParameter(), classInit));
    }

    private void processClassParameter(Symbol.TypeVariableSymbol typeVariable, ReifiedCall init) {
        var invocation = (JCTree.JCNewClass) init.invocation();
        var clazz = init.enclosingClass();
        var method = init.enclosingMethod();

        var type = simpleClasses.resolveClassType(
                typeVariable,
                invocation,
                init.invoked(),
                clazz,
                method,
                init.enclosingStatement()
        );

        invocation.args = invocation.args.prepend(createClassLiteral(type, clazz, method));
    }

    public JCTree.JCExpression createClassLiteral(Type type, JCTree.JCClassDecl clazz, JCTree.JCMethodDecl method){
        if(!simpleTypes.isGeneric(type)){
            return simpleMaker.classLiteral(simpleTypes.resolveWildCard(type));
        }

        var typeSymbol = (Symbol.TypeVariableSymbol) type.asElement().baseSymbol();
        if (!simpleTypes.isReified(typeSymbol)) {
            processTypeParameter(typeSymbol, clazz, method);
        }

        var name = type.asElement().getSimpleName();
        if(method == null){
            return clazz.getMembers()
                    .stream()
                    .filter(tree -> tree.getTag() == JCTree.Tag.VARDEF)
                    .map(tree -> (JCTree.JCVariableDecl) tree)
                    .filter(variable -> variable.getName().contentEquals(name))
                    .findFirst()
                    .map(variable -> variable.sym)
                    .map(simpleMaker::identity)
                    .orElseThrow(() -> new AssertionError("Nested reified parameter cannot be processed if enclosing parameters have not been processed yet"));
        }

        return method.sym.getParameters()
                .stream()
                .filter(param -> param.getSimpleName().contentEquals(name))
                .findFirst()
                .map(simpleMaker::identity)
                .orElseThrow(() -> new AssertionError("Nested reified parameter cannot be processed if enclosing parameters have not been processed yet"));
    }

    private void processTypeParameter(Symbol.TypeVariableSymbol typeSymbol, JCTree.JCClassDecl clazz, JCTree.JCMethodDecl method) {
        var candidate = new ReifiedCandidate(typeSymbol, clazz, findEnclosingMethod(typeSymbol, method));
        var declaration = parseCandidate(candidate);
        processTypeParameter(declaration);
        simpleMaker.processMembers(declaration);
    }

    private JCTree.JCMethodDecl findEnclosingMethod(Symbol.TypeVariableSymbol typeSymbol, JCTree.JCMethodDecl method) {
        return typeSymbol.owner instanceof Symbol.ClassSymbol ? null : method;
    }

    private Set<JCTree> findCompilationUnits(ReifiedDeclaration reifiedDeclaration) {
        return environment.getRootElements()
                .stream()
                .map(element -> simpleTypes.findClassEnv(trees.getTree(element)))
                .onlyPresent()
                .filter(unit -> checkClassScope(reifiedDeclaration, unit))
                .map(env -> env.tree)
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean checkClassScope(ReifiedDeclaration reifiedDeclaration, Env<AttrContext> unit) {
        var paramEnv = simpleTypes.findClassEnv(reifiedDeclaration.enclosingClass());
        switch (reifiedDeclaration.modifier()) {
            case PUBLIC:
                return true;
            case PRIVATE:
                return Objects.equals(unit.toplevel, paramEnv.toplevel);
            case PROTECTED:
            case PACKAGE_PRIVATE:
                return Objects.equals(unit.toplevel.packge, paramEnv.toplevel.packge);
            default:
                throw new IllegalArgumentException("Unknown modifier: " + reifiedDeclaration.modifier());
        }
    }
}
