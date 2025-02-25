package norswap.sigh;

import norswap.sigh.ast.*;
import norswap.sigh.scopes.*;
import norswap.sigh.types.*;
import norswap.uranium.Attribute;
import norswap.uranium.Reactor;
import norswap.uranium.Rule;
import norswap.uranium.SemanticError;
import norswap.utils.visitors.ReflectiveFieldWalker;
import norswap.utils.visitors.Walker;

import java.util.*;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static norswap.sigh.ast.BinaryOperator.*;
import static norswap.utils.Util.cast;
import static norswap.utils.Vanilla.forEachIndexed;
import static norswap.utils.Vanilla.list;
import static norswap.utils.visitors.WalkVisitType.POST_VISIT;
import static norswap.utils.visitors.WalkVisitType.PRE_VISIT;

/**
 * Holds the logic implementing semantic analyzis for the language, including typing and name
 * resolution.
 *
 * <p>The entry point into this class is {@link #createWalker(Reactor)}.
 *
 * <h2>Big Principles
 * <ul>
 *     <li>Every {@link DeclarationNode} instance must have its {@code type} attribute to an
 *     instance of {@link Type} which is the type of the value declared (note that for struct
 *     declaration, this is always {@link TypeType}.</li>
 *
 *     <li>Additionally, {@link StructDeclarationNode} (and default
 *     {@link SyntheticDeclarationNode} for types) must have their {@code declared} attribute set to
 *     an instance of the type being declared.</li>
 *
 *     <li>Every {@link ExpressionNode} instance must have its {@code type} attribute similarly
 *     set.</li>
 *
 *     <li>Every {@link ReferenceNode} instance must have its {@code decl} attribute set to the the
 *     declaration it references and its {@code scope} attribute set to the {@link Scope} in which
 *     the declaration it references lives. This speeds up lookups in the interpreter and simplifies the compiler.</li>
 *
 *     <li>For the same reasons, {@link VarDeclarationNode} and {@link ParameterNode} should have
 *     their {@code scope} attribute set to the scope in which they appear (this also speeds up the
 *     interpreter).</li>
 *
 *     <li>All statements introducing a new scope must have their {@code scope} attribute set to the
 *     corresponding {@link Scope} (only {@link RootNode}, {@link BlockNode} and {@link
 *     FunDeclarationNode} (for parameters)). These nodes must also update the {@code scope}
 *     field to track the current scope during the walk.</li>
 *
 *     <li>Every {@link TypeNode} instance must have its {@code value} set to the {@link Type} it
 *     denotes.</li>
 *
 *     <li>Every {@link ReturnNode}, {@link BlockNode} and {@link IfNode} must have its {@code
 *     returns} attribute set to a boolean to indicate whether its execution causes
 *     unconditional exit from the surrounding function or main script.</li>
 *
 *     <li>The rules check typing constraints: assignment of values to variables, of arguments to
 *     parameters, checking that if/while conditions are booleans, and array indices are
 *     integers.</li>
 *
 *     <li>The rules also check a number of other constraints: that accessed struct fields exist,
 *     that variables are declared before being used, etc...</li>
 * </ul>
 */
public final class SemanticAnalysis
{
    // =============================================================================================
    // region [Initialization]
    // =============================================================================================

    private final Reactor R;

    /** Current scope. */
    private Scope scope;

    /** Current context for type inference (currently only to infer the type of empty arrays). */
    private SighNode inferenceContext;

    /** Every class scope */
    private final HashMap<ClassDeclarationNode, ClassScope> classScopes = new HashMap<>();

    /** Index of the current function argument. */
    private int argumentIndex;

    /** Index of the current thread */
    private int threadIndex = 0;

    // ---------------------------------------------------------------------------------------------

    private SemanticAnalysis(Reactor reactor) {
        this.R = reactor;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Call this method to create a tree walker that will instantiate the typing rules defined
     * in this class when used on an AST, using the given {@code reactor}.
     */
    public static Walker<SighNode> createWalker (Reactor reactor)
    {
        ReflectiveFieldWalker<SighNode> walker = new ReflectiveFieldWalker<>(
            SighNode.class, PRE_VISIT, POST_VISIT);

        SemanticAnalysis analysis = new SemanticAnalysis(reactor);

        // expressions
        walker.register(IntLiteralNode.class,           PRE_VISIT,  analysis::intLiteral);
        walker.register(FloatLiteralNode.class,         PRE_VISIT,  analysis::floatLiteral);
        walker.register(StringLiteralNode.class,        PRE_VISIT,  analysis::stringLiteral);
        walker.register(ReferenceNode.class,            PRE_VISIT,  analysis::reference);
        walker.register(ConstructorNode.class,          PRE_VISIT,  analysis::constructor);
        walker.register(ArrayLiteralNode.class,         PRE_VISIT,  analysis::arrayLiteral);
        walker.register(ParenthesizedNode.class,        PRE_VISIT,  analysis::parenthesized);
        walker.register(FieldAccessNode.class,          PRE_VISIT,  analysis::fieldAccess);
        walker.register(ArrayAccessNode.class,          PRE_VISIT,  analysis::arrayAccess);
        walker.register(FunCallNode.class,              PRE_VISIT,  analysis::funCall);
        walker.register(DaddyCallNode.class,            PRE_VISIT,  analysis::daddyCall);
        walker.register(UnaryExpressionNode.class,      PRE_VISIT,  analysis::unaryExpression);
        walker.register(BinaryExpressionNode.class,     PRE_VISIT,  analysis::binaryExpression);
        walker.register(AssignmentNode.class,           PRE_VISIT,  analysis::assignment);
        walker.register(BornNode.class,                 PRE_VISIT,  analysis::bornExpression);

        // types
        walker.register(SimpleTypeNode.class,           PRE_VISIT,  analysis::simpleType);
        walker.register(ArrayTypeNode.class,            PRE_VISIT,  analysis::arrayType);
        walker.register(UnbornTypeNode.class,           PRE_VISIT,  analysis::unbornType);

        // declarations & scopes
        walker.register(RootNode.class,                 PRE_VISIT,  analysis::root);
        walker.register(BlockNode.class,                PRE_VISIT,  analysis::block);
        walker.register(VarDeclarationNode.class,       PRE_VISIT,  analysis::varDecl);
        walker.register(FieldDeclarationNode.class,     PRE_VISIT,  analysis::fieldDecl);
        walker.register(ParameterNode.class,            PRE_VISIT,  analysis::parameter);
        walker.register(FunDeclarationNode.class,       PRE_VISIT,  analysis::funDecl);
        walker.register(MethodDeclarationNode.class,    PRE_VISIT,  analysis::funDecl);
        walker.register(StructDeclarationNode.class,    PRE_VISIT,  analysis::structDecl);
        walker.register(ClassDeclarationNode.class,     PRE_VISIT,  analysis::classDecl);

        walker.register(RootNode.class,                 POST_VISIT, analysis::popScope);
        walker.register(BlockNode.class,                POST_VISIT, analysis::popScope);
        walker.register(FunDeclarationNode.class,       POST_VISIT, analysis::popScope);
        walker.register(MethodDeclarationNode.class,    POST_VISIT, analysis::popScope);
        walker.register(ClassDeclarationNode.class,     POST_VISIT, analysis::popScope);

        // statements
        walker.register(ExpressionStatementNode.class,  PRE_VISIT,  node -> {});
        walker.register(IfNode.class,                   PRE_VISIT,  analysis::ifStmt);
        walker.register(WhileNode.class,                PRE_VISIT,  analysis::whileStmt);
        walker.register(ReturnNode.class,               PRE_VISIT,  analysis::returnStmt);

        walker.registerFallback(POST_VISIT, node -> {});

        return walker;
    }

    // endregion
    // =============================================================================================
    // region [Expressions]
    // =============================================================================================

    private void intLiteral (IntLiteralNode node) {
        R.set(node, "type", IntType.INSTANCE);
    }

    // ---------------------------------------------------------------------------------------------

    private void floatLiteral (FloatLiteralNode node) {
        R.set(node, "type", FloatType.INSTANCE);
    }

    // ---------------------------------------------------------------------------------------------

    private void stringLiteral (StringLiteralNode node) {
        R.set(node, "type", StringType.INSTANCE);
    }

    // ---------------------------------------------------------------------------------------------

    private void reference (ReferenceNode node)
    {
        final Scope scope = this.scope;
        R.set(node, "threadIndex", threadIndex);

        if (node.name.equals("Daddy")){
            // Nothing to do here with Daddy references
            return;
        }

        // Try to lookup immediately. This must succeed for variables, but not necessarily for
        // functions or types. By looking up now, we can report looked up variables later
        // as being used before being defined.
        DeclarationContext maybeCtx = scope.lookup(node.name);

        if (maybeCtx != null) {
            R.set(node, "decl",  maybeCtx.declaration);
            R.set(node, "scope", maybeCtx.scope);

            R.rule(node, "type")
            .using(maybeCtx.declaration, "type")
            .by( r -> {
                r.set(0, r.get(0));
            });
            return;
        }

        // Re-lookup after the scopes have been built.
        R.rule(node.attr("decl"), node.attr("scope"))
        .by(r -> {
            DeclarationContext ctx = scope.lookup(node.name);
            DeclarationNode decl = ctx == null ? null : ctx.declaration;

            if (ctx == null) {
                r.errorFor("Could not resolve: " + node.name,
                    node, node.attr("decl"), node.attr("scope"), node.attr("type"));
            }
            else {
                r.set(node, "scope", ctx.scope);
                r.set(node, "decl", decl);

                if (decl instanceof VarDeclarationNode)
                    r.errorFor("Variable used before declaration: " + node.name,
                        node, node.attr("type"));
                else
                    R.rule(node, "type")
                    .using(decl, "type")
                    .by(Rule::copyFirst);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void constructor (ConstructorNode node)
    {
        R.rule()
        .using(node.ref, "decl")
        .by(r -> {
            DeclarationNode decl = r.get(0);

            if (!(decl instanceof StructDeclarationNode)) {
                String description =
                        "Applying the constructor operator ($) to non-struct reference for: "
                        + decl;
                r.errorFor(description, node, node.attr("type"));
                return;
            }

            StructDeclarationNode structDecl = (StructDeclarationNode) decl;

            Attribute[] dependencies = new Attribute[structDecl.fields.size() + 1];
            dependencies[0] = decl.attr("declared");
            forEachIndexed(structDecl.fields, (i, field) ->
                dependencies[i + 1] = field.attr("type"));

            R.rule(node, "type")
            .using(dependencies)
            .by(rr -> {
                Type structType = rr.get(0);
                Type[] params = IntStream.range(1, dependencies.length).<Type>mapToObj(rr::get)
                        .toArray(Type[]::new);
                rr.set(0, new FunType(structType, params));
            });
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void arrayLiteral (ArrayLiteralNode node)
    {
        if (node.components.size() == 0) { // []
            // Empty array: we need a type int to know the desired type.

            final SighNode context = this.inferenceContext;

            if (context instanceof VarDeclarationNode)
                R.rule(node, "type")
                .using(context, "type")
                .by(Rule::copyFirst);
            else if (context instanceof FunCallNode) {
                R.rule(node, "type")
                .using(((FunCallNode) context).function.attr("type"), node.attr("index"))
                .by(r -> {
                    FunType funType = r.get(0);
                    r.set(0, funType.paramTypes[(int) r.get(1)]);
                });
            }
            return;
        }

        Attribute[] dependencies =
            node.components.stream().map(it -> it.attr("type")).toArray(Attribute[]::new);

        R.rule(node, "type")
        .using(dependencies)
        .by(r -> {
            Type[] types = IntStream.range(0, dependencies.length).<Type>mapToObj(r::get)
                    .distinct().toArray(Type[]::new);

            int i = 0;
            Type supertype = null;
            for (Type type: types) {
                if (type instanceof VoidType)
                    // We report the error, but compute a type for the array from the other elements.
                    r.errorFor("Void-valued expression in array literal", node.components.get(i));
                else if (supertype == null)
                    supertype = type;
                else {
                    supertype = commonSupertype(supertype, type);
                    if (supertype == null) {
                        r.error("Could not find common supertype in array literal.", node);
                        return;
                    }
                }
                ++i;
            }

            if (supertype == null)
                r.error(
                    "Could not find common supertype in array literal: all members have Void type.",
                    node);
            else
                r.set(0, new ArrayType(supertype));
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void parenthesized (ParenthesizedNode node)
    {
        R.rule(node, "type")
        .using(node.expression, "type")
        .by(Rule::copyFirst);
    }

    // ---------------------------------------------------------------------------------------------

    private void fieldAccess (FieldAccessNode node)
    {
        R.rule()
        .using(node.stem, "type")
        .by(r -> {
            Type type = r.get(0);

            if (type instanceof ArrayType) {
                if (node.fieldName.equals("length"))
                    R.rule(node, "type")
                    .by(rr -> rr.set(0, IntType.INSTANCE));
                else
                    r.errorFor("Trying to access a non-length field on an array", node,
                        node.attr("type"));
                return;
            }
            
            if (!(type instanceof StructType) && !(type instanceof ClassType)) {
                r.errorFor("Trying to access a field on an expression of type " + type,
                        node,
                        node.attr("type"));
                return;
            }

            if (type instanceof ClassType) {
                ClassType classType = (ClassType) type;
                Type fieldType = classType.hasField(node.fieldName);
                if (fieldType == null) {
                    String description = format("Field '%s' not defined in class '%s'",
                                                node.fieldName, classType.name);
                    r.errorFor(description, node,
                        node.attr("type"));
                } else {
                    if (fieldType instanceof FunType)
                        if (((FunType) fieldType).returnType instanceof UnbornType) {
                            r.errorFor("Async methods can only be called from within the class", node, node.attr("type"));
                            return;
                        }
                    R.rule(node, "type").by(rr -> rr.set(0, fieldType));
                }
            } else {
                StructDeclarationNode decl = ((StructType) type).node;

                for (DeclarationNode field: decl.fields)
                {
                    if (!field.name().equals(node.fieldName)) continue;

                    R.rule(node, "type")
                            .using(field, "type")
                            .by(Rule::copyFirst);

                    return;
                }

                String description = format("Trying to access missing field %s on struct %s",
                        node.fieldName, decl.name);
                r.errorFor(description, node, node.attr("type"));

            }

        });
    }

    // ---------------------------------------------------------------------------------------------

    private void arrayAccess (ArrayAccessNode node)
    {
        R.rule()
        .using(node.index, "type")
        .by(r -> {
            Type type = r.get(0);
            if (!(type instanceof IntType))
                r.error("Indexing an array using a non-Int-valued expression", node.index);
        });

        R.rule(node, "type")
        .using(node.array, "type")
        .by(r -> {
            Type type = r.get(0);
            if (type instanceof ArrayType)
                r.set(0, ((ArrayType) type).componentType);
            else
                r.error("Trying to index a non-array expression of type " + type, node);
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void funCall (FunCallNode node)
    {
        this.inferenceContext = node;
        R.set(node, "threadIndex", threadIndex);

        Attribute[] dependencies = new Attribute[node.arguments.size() + 1];
        dependencies[0] = node.function.attr("type");
        forEachIndexed(node.arguments, (i, arg) -> {
            dependencies[i + 1] = arg.attr("type");
            R.set(arg, "index", i);
        });

        R.rule(node, "type")
        .using(dependencies)
        .by(r -> {
            Type maybeFunType = r.get(0);

            if (!(maybeFunType instanceof FunType) && !(maybeFunType instanceof ClassType)) {
                r.error("trying to call a non-function/non-class expression: " + node.function, node.function);
                return;
            }

            FunType funType;

            if (maybeFunType instanceof ClassType) {
                ClassType classType = (ClassType) maybeFunType;
                funType = classType.getConstructor();
                if (funType == null) {
                    r.error("Cannot instantiate a class that doesn't implement a constructor", node.function);
                    return;
                }
                r.set(0, classType);
            } else {
                funType = cast(maybeFunType);
                r.set(0, funType.returnType);
            }


            Type[] params = funType.paramTypes;
            List<ExpressionNode> args = node.arguments;

            if (params.length != args.size())
                r.errorFor(format("wrong number of arguments, expected %d but got %d",
                        params.length, args.size()),
                    node);

            int checkedArgs = Math.min(params.length, args.size());

            for (int i = 0; i < checkedArgs; ++i) {
                Type argType = r.get(i + 1);
                Type paramType = funType.paramTypes[i];
                if (!isAssignableTo(argType, paramType))
                    r.errorFor(format(
                            "incompatible argument provided for argument %d in %s: expected %s but got %s",
                            i, node.function.contents(),paramType, argType),
                        node.arguments.get(i));
            }
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void daddyCall(DaddyCallNode node) {
        R.set(node, "threadIndex", threadIndex);

        final Scope scope = this.scope;

        R.rule().by(r -> {
           // Check parent scopes
            boolean found = false;
            MethodDeclarationNode methodDecl = null;
            Scope iterationScope = scope;
            while (!found){
                if (iterationScope.node instanceof MethodDeclarationNode) {
                    found = true;
                    methodDecl = (MethodDeclarationNode) iterationScope.node;
                } else if (iterationScope.node instanceof FunDeclarationNode || iterationScope.node instanceof RootNode || iterationScope.node instanceof ClassDeclarationNode){
                    r.error("Daddy calls must be located inside a method", node);
                    break;
                } else {
                    iterationScope = iterationScope.parent;
                }
            }
            if (found) {
                R.rule(node, "parent").using(methodDecl.attr("parent")).by(rr -> {
                   Object parentDecl = rr.get(0);
                   // If the current function has no parent implementation
                   if (parentDecl instanceof VoidType) {
                       rr.error("Trying to make a Daddy call in a method that has no parent, that's cruel man.", node);
                       rr.errorFor("Cannot find parent type, does it exists ?", node, node.attr("type"));
                   } else {
                       rr.set(0, (MethodDeclarationNode) parentDecl);
                       R.rule(node.attr( "type"), node.attr("parentType")).using(parentDecl, "type").by(rrr -> {
                           rrr.set(0, ((FunType) rrr.get(0)).returnType);
                           rrr.set(1, rrr.get(0));
                       });
                   }
                });
            } else {
                R.rule(node, "parent").by(rr -> {
                   rr.errorFor("Could not resolve Daddy call", node, node.attr("parent"));
                    rr.errorFor("Cannot find parent type, does it exists ?", node, node.attr("type"));
                });
            }
        });

        Attribute[] dependencies = new Attribute[node.arguments.size() + 1];
        dependencies[0] =  node.attr("parentType");
        forEachIndexed(node.arguments, (i, arg) -> {
            dependencies[i + 1] = arg.attr("type");
            R.set(arg, "index", i);
        });

        R.rule()
            .using(dependencies)
            .by (r -> {
                FunType funType = r.get(0);

                Type[] params = funType.paramTypes;
                List<ExpressionNode> args = node.arguments;

                if (params.length != args.size())
                    r.errorFor(format("wrong number of arguments in Daddy call,  parent expects %d but got %d",
                                    params.length, args.size()),
                            node);

                int checkedArgs = Math.min(params.length, args.size());

                for (int i = 0; i < checkedArgs; ++i) {
                    Type argType = r.get(i + 1);
                    Type paramType = funType.paramTypes[i];
                    if (!isAssignableTo(argType, paramType))
                        r.errorFor(format(
                                        "incompatible argument provided for argument %d in %s: expected %s but got %s",
                                        i, node.function.contents(),paramType, argType),
                                node.arguments.get(i));
                }
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void unaryExpression (UnaryExpressionNode node)
    {
        assert node.operator == UnaryOperator.NOT; // only one for now
        R.set(node, "type", BoolType.INSTANCE);

        R.rule()
        .using(node.operand, "type")
        .by(r -> {
            Type opType = r.get(0);
            if (!(opType instanceof BoolType))
                r.error("Trying to negate type: " + opType, node);
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void bornExpression (BornNode node) {
        R.set(node, "threadIndex", threadIndex);
        R.set(node, "scope", scope);
        
        final Scope scope = this.scope;

        R.rule()
        .using(node.function.attr("type"))
        .by(r -> {
            DeclarationNode funDecl = scope.lookup(node.function.name).declaration;

            // Check if the function name refers effectively to a function declaration
            if (!(funDecl instanceof FunDeclarationNode)) {
                r.error("First parameter of born node must refer to a declared function.", node);
            } else {
                // Check if the function return type to be born is Unborn
                if (!(((FunType) r.get(0)).returnType instanceof UnbornType))
                    r.error("Trying to born a non-Unborn function.", node);
                else {
                    Type componentType = ((UnbornType)((FunType) r.get(0)).returnType).componentType;
                    if (!(node.variable == null)) {
                        // Born async function with return value
                        if (componentType instanceof VoidType) {
                            // Born Void async function
                            r.error("Cannot assign the return value of a Void Unborn function to a variable: call born() with the function name only.", node);
                        } else {
                            R.rule()
                            .using(node.variable.attr("type"))
                            .by(r2 -> {
                                // Check if the variable name refers effectively to a variable declaration
                                DeclarationNode varDecl = scope.lookup(node.variable.name).declaration;
    
                                if (!(varDecl instanceof VarDeclarationNode)) {
                                    r2.error("Second parameter of born node must refer to a declared variable.", node);    
                                } else {
                                    // Check if the variable type matches the Unborn inner type
                                    Type variableType = r2.get(0);
                                    if(!(componentType.equals(variableType))) {
                                        r2.error("Variable type does not match the Unborn function inner type (expected " + componentType + " but got " + variableType + ")", node);
                                    }
                                }
                            });
                        }
                    }
                }
            }
        });
    }

    // endregion
    // =============================================================================================
    // region [Binary Expressions]
    // =============================================================================================

    private void binaryExpression (BinaryExpressionNode node)
    {
        R.rule(node, "type")
        .using(node.left.attr("type"), node.right.attr("type"))
        .by(r -> {
            Type left  = r.get(0);
            Type right = r.get(1);

            if (node.operator == ADD && (left instanceof StringType || right instanceof StringType))
                r.set(0, StringType.INSTANCE);
            else if (node.operator == CIBLINGS)
                ciblingsCheck(r,node, left, right);
            else if (isArithmetic(node.operator))
                binaryArithmetic(r, node, left, right);
            else if (isComparison(node.operator))
                binaryComparison(r, node, left, right);
            else if (isLogic(node.operator))
                binaryLogic(r, node, left, right);
            else if (isEquality(node.operator))
                binaryEquality(r, node, left, right);
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void ciblingsCheck (Rule r, BinaryExpressionNode node, Type left, Type right)
    {
        if (!(left instanceof ClassType && right instanceof ClassType)) {
            r.error("Trying to check if two non-classes are siblings.", node);
        } else {
            r.set(0, BoolType.INSTANCE);
        }
    }


    // ---------------------------------------------------------------------------------------------

    private boolean isArithmetic (BinaryOperator op) {
        return op == ADD || op == MULTIPLY || op == SUBTRACT || op == DIVIDE || op == REMAINDER;
    }

    private boolean isComparison (BinaryOperator op) {
        return op == GREATER || op == GREATER_EQUAL || op == LOWER || op == LOWER_EQUAL;
    }

    private boolean isLogic (BinaryOperator op) {
        return op == OR || op == AND;
    }

    private boolean isEquality (BinaryOperator op) {
        return op == EQUALITY || op == NOT_EQUALS;
    }

    // ---------------------------------------------------------------------------------------------

    private void binaryArithmetic (Rule r, BinaryExpressionNode node, Type left, Type right)
    {
        if (left instanceof IntType)
            if (right instanceof IntType)
                r.set(0, IntType.INSTANCE);
            else if (right instanceof FloatType)
                r.set(0, FloatType.INSTANCE);
            else
                r.error(arithmeticError(node, "Int", right), node);
        else if (left instanceof FloatType)
            if (right instanceof IntType || right instanceof FloatType)
                r.set(0, FloatType.INSTANCE);
            else
                r.error(arithmeticError(node, "Float", right), node);
        else
            r.error(arithmeticError(node, left, right), node);
    }

    // ---------------------------------------------------------------------------------------------

    private static String arithmeticError (BinaryExpressionNode node, Object left, Object right) {
        return format("Trying to %s %s with %s", node.operator.name().toLowerCase(), left, right);
    }

    // ---------------------------------------------------------------------------------------------

    private void binaryComparison (Rule r, BinaryExpressionNode node, Type left, Type right)
    {
        r.set(0, BoolType.INSTANCE);

        if (!(left instanceof IntType) && !(left instanceof FloatType))
            r.errorFor("Attempting to perform arithmetic comparison on non-numeric type: " + left,
                node.left);
        if (!(right instanceof IntType) && !(right instanceof FloatType))
            r.errorFor("Attempting to perform arithmetic comparison on non-numeric type: " + right,
                node.right);
    }

    // ---------------------------------------------------------------------------------------------

    private void binaryEquality (Rule r, BinaryExpressionNode node, Type left, Type right)
    {
        r.set(0, BoolType.INSTANCE);

        if (!isComparableTo(left, right))
            r.errorFor(format("Trying to compare incomparable types %s and %s", left, right),
                node);
    }

    // ---------------------------------------------------------------------------------------------

    private void binaryLogic (Rule r, BinaryExpressionNode node, Type left, Type right)
    {
        r.set(0, BoolType.INSTANCE);

        if (!(left instanceof BoolType))
            r.errorFor("Attempting to perform binary logic on non-boolean type: " + left,
                node.left);
        if (!(right instanceof BoolType))
            r.errorFor("Attempting to perform binary logic on non-boolean type: " + right,
                node.right);
    }

    // ---------------------------------------------------------------------------------------------

    private void assignment (AssignmentNode node)
    {
        R.set(node, "threadIndex", threadIndex);
        R.rule(node, "type")
        .using(node.left.attr("type"), node.right.attr("type"))
        .by(r -> {
            Type left  = r.get(0);
            Type right = r.get(1);

            r.set(0, r.get(0)); // the type of the assignment is the left-side type

            if (node.left instanceof ReferenceNode
            ||  node.left instanceof FieldAccessNode
            ||  node.left instanceof ArrayAccessNode) {
                if (left instanceof ClassType){
                    StringBuilder sb = new StringBuilder();
                    boolean assignable = ((ClassType) left).canBeAssignedWith(right, sb);
                    if (!assignable)
                        r.errorFor(sb.toString(), node.left);
                } else {
                    if (!isAssignableTo(right, left))
                            r.errorFor(format("Trying to assign %s with a non-compatible value. Expected %s but got %s",
                                    node.left, left, right), node);
                }
            }
            else
                r.errorFor("Trying to assign to an non-lvalue expression.", node.left);
        });
    }

    // endregion
    // =============================================================================================
    // region [Types & Typing Utilities]
    // =============================================================================================

    private void simpleType (SimpleTypeNode node)
    {
        final Scope scope = this.scope;

        R.rule()
        .by(r -> {
            // type declarations may occur after use
            DeclarationContext ctx = scope.lookup(node.name);
            DeclarationNode decl = ctx == null ? null : ctx.declaration;

            if (ctx == null)
                r.errorFor("could not resolve: " + node.name,
                    node,
                    node.attr("value"));

            else if (!isTypeDecl(decl))
                r.errorFor(format(
                    "%s did not resolve to a type declaration but to a %s declaration",
                    node.name, decl.declaredThing()),
                    node,
                    node.attr("value"));

            else
                R.rule(node, "value")
                .using(decl, "declared")
                .by(rr ->
                    {
                        rr.set(0, rr.get(0));
                    });
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void arrayType (ArrayTypeNode node)
    {
        R.rule(node, "value")
        .using(node.componentType, "value")
        .by(r ->
                {
                    r.set(0, new ArrayType(r.get(0)));
                });
    }

    // ---------------------------------------------------------------------------------------------

    private void unbornType (UnbornTypeNode node)
    {
        R.rule(node, "value")
        .using(node.componentType, "value")
        .by(r -> r.set(0, new UnbornType(r.get(0))));
    }

    // ---------------------------------------------------------------------------------------------

    private static boolean isTypeDecl (DeclarationNode decl)
    {
        if (decl instanceof StructDeclarationNode) return true;
        if (decl instanceof ClassDeclarationNode) return true;
        if (!(decl instanceof SyntheticDeclarationNode)) return false;
        SyntheticDeclarationNode synthetic = cast(decl);
        return synthetic.kind() == DeclarationKind.TYPE;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Indicates whether a value of type {@code a} can be assigned to a location (variable,
     * parameter, ...) of type {@code b}.
     */
    private static boolean isAssignableTo (Type a, Type b)
    {
        if (b instanceof  AutoType) return true;

        if (a instanceof VoidType || b instanceof VoidType)
            return false;

        if (a instanceof IntType && b instanceof FloatType)
            return true;

        if (a instanceof ArrayType)
            return b instanceof ArrayType
                && isAssignableTo(((ArrayType)a).componentType, ((ArrayType)b).componentType);

        if (a instanceof UnbornType)
            return b instanceof UnbornType
                && isAssignableTo(((UnbornType)a).componentType, ((UnbornType)b).componentType);

        return a instanceof NullType && b.isReference() || a.equals(b);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Indicate whether the two types are comparable.
     */
    private static boolean isComparableTo (Type a, Type b)
    {
        if (a instanceof VoidType || b instanceof VoidType)
            return false;

        return a.isReference() && b.isReference()
            || a.equals(b)
            || a instanceof IntType && b instanceof FloatType
            || a instanceof FloatType && b instanceof IntType;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the common supertype between both types, or {@code null} if no such supertype
     * exists.
     */
    private static Type commonSupertype (Type a, Type b)
    {
        if (a instanceof VoidType || b instanceof VoidType)
            return null;
        if (isAssignableTo(a, b))
            return b;
        if (isAssignableTo(b, a))
            return a;
        else
            return null;
    }

    // endregion
    // =============================================================================================
    // region [Scopes & Declarations]
    // =============================================================================================

    private void popScope (SighNode node) {
        scope = scope.parent;

        if (node instanceof FunDeclarationNode) {
            threadIndex = 0;
        }
    }

    // ---------------------------------------------------------------------------------------------

    private void root (RootNode node) {
        assert scope == null;
        scope = new RootScope(node, R);
        R.set(node, "scope", scope);
    }

    // ---------------------------------------------------------------------------------------------

    private void block (BlockNode node) {
        scope = new Scope(node, scope);
        R.set(node, "scope", scope);
        R.set(node, "threadIndex", threadIndex);

        Attribute[] deps = getReturnsDependencies(node.statements);
        R.rule(node, "returns")
        .using(deps)
        .by(r -> r.set(0, deps.length != 0 && Arrays.stream(deps).anyMatch(r::get)));
    }

    // ---------------------------------------------------------------------------------------------

    private void varDecl (VarDeclarationNode node)
    {
        this.inferenceContext = node;
        R.set(node, "threadIndex", threadIndex);

        scope.declare(node.name, node);
        R.set(node, "scope", scope);


        if (node.initializer instanceof ArrayLiteralNode) {
            R.rule(node, "type")
                    .using(node.type, "value")
                    .by(r -> {
                        r.set(0, r.get(0));
                    });
        } else {
            R.rule(node, "type").using(node.type.attr("value"), node.initializer.attr("type")).by(r -> {
                Type expected = r.get(0);
                Type actual = r.get(1);
                if (expected instanceof AutoType) {
                    r.set(0, actual);
                } else {
                    r.set(0, expected);
                }
            });
        }

        R.rule()
        .using(node.type.attr("value"), node.initializer.attr("type"))
        .by(r -> {
            Type expected = r.get(0);
            Type actual = r.get(1);

            if (expected instanceof AutoType && actual instanceof AutoType) {
                r.error("Cannot infer type of variable '" + node.name + "'. Are you messing with Auto variables ?", node);
            }

            if (expected instanceof AutoType && actual instanceof ArrayType) {
                r.error("Auto type is not allowed for arrays", node);
            }



            if (expected instanceof ClassType){
                StringBuilder sb = new StringBuilder();
                boolean assignable = ((ClassType) expected).canBeAssignedWith(actual, sb);
                if (!assignable)
                    r.error(sb.toString(), node.initializer);
            } else {
                if (!isAssignableTo(actual, expected))
                    r.error(format(
                                    "incompatible initializer type provided for variable `%s`: expected %s but got %s",
                                    node.name, expected, actual),
                            node.initializer);
            }

        });
    }

    // ---------------------------------------------------------------------------------------------

    private void fieldDecl (FieldDeclarationNode node)
    {
        R.rule(node, "type")
        .using(node.type, "value")
        .by(Rule::copyFirst);
    }

    // ---------------------------------------------------------------------------------------------

    private void parameter (ParameterNode node)
    {
        R.set(node, "scope", scope);
        scope.declare(node.name, node); // scope pushed by FunDeclarationNode

        R.rule(node, "type")
        .using(node.type, "value")
        .by(Rule::copyFirst);
    }

    // ---------------------------------------------------------------------------------------------

    private void funDecl (FunDeclarationNode node)
    {
        // Check if the function declaration is a constructor.
        if (scope.node instanceof ClassDeclarationNode) {
            ClassDeclarationNode classDecl = (ClassDeclarationNode) scope.node;
            if (classDecl.name.equals(node.name)) {
                // Rename the function
                node.name = "<constructor>";
            }
        }

        // Check that the function isn't called 'Daddy'
        R.rule().by(r -> {
            if (node.name.equals("Daddy")){
                r.error("Functions and Methods can't be named 'Daddy'", node);
            }
        });

        // Check if the declared function is async
        if (node.returnType instanceof UnbornTypeNode) {
            threadIndex = node.hashCode();
        }

        scope.declare(node.name, node);
        scope = new Scope(node, scope);
        R.set(node, "scope", scope);
        R.set(node, "threadIndex", threadIndex);

        if (node instanceof MethodDeclarationNode) {
            // Get class declaration
            boolean found = false;
            ClassDeclarationNode classDeclarationNode = null;
            Scope iteration_scope = this.scope;
            while(!found){
                if (iteration_scope.node instanceof ClassDeclarationNode) {
                    found = true;
                    classDeclarationNode = (ClassDeclarationNode) iteration_scope.node;
                } else {
                    iteration_scope = iteration_scope.parent;
                }
            }
            // Retrieve the class scope
            if (classDeclarationNode.parent != null && this.scope.lookup(classDeclarationNode.parent) != null && this.scope.lookup(classDeclarationNode.parent).declaration instanceof ClassDeclarationNode) {
                ClassDeclarationNode parentClassDeclarationNode = (ClassDeclarationNode) this.scope.lookup(classDeclarationNode.parent).declaration;
                R.rule(node, "parent").using(parentClassDeclarationNode.attr("scope")).by(r -> {
                    Scope classScope = (Scope) r.get(0);
                    DeclarationContext ctx = classScope.lookup(node.name);
                    if (ctx == null) {
                        r.set(0, VoidType.INSTANCE);
                    } else {
                        MethodDeclarationNode parentDecl = (MethodDeclarationNode) ctx.declaration;
                        r.set(0, parentDecl);
                    }
                });
            } else {
                R.set(node, "parent", VoidType.INSTANCE);
            }

        }

        Attribute[] dependencies = new Attribute[node.parameters.size() + 1];
        dependencies[0] = node.returnType.attr("value");
        forEachIndexed(node.parameters, (i, param) ->
            dependencies[i + 1] = param.attr("type"));

        R.rule(node, "type")
        .using(dependencies)
        .by (r -> {
            Type[] paramTypes = new Type[node.parameters.size()];
            for (int i = 0; i < paramTypes.length; ++i)
                paramTypes[i] = r.get(i + 1);
            if (node.name.equals("<constructor>") && !(r.get(0) instanceof VoidType)){
                r.error("constructor must return void", node.returnType);
            } else {
                r.set(0, new FunType(r.get(0), paramTypes));
            }
        });

        R.rule()
        .using(node.block.attr("returns"), node.returnType.attr("value"))
        .by(r -> {
            boolean returns = r.get(0);
            Type returnType = r.get(1);

            if (returnType instanceof UnbornType)
                returnType = ((UnbornType) returnType).componentType;

            if (!returns && !(returnType instanceof VoidType))
                r.error("Missing return in function.", node);
            // NOTE: The returned value presence & type is checked in returnStmt().
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void structDecl (StructDeclarationNode node) {
        scope.declare(node.name, node);
        R.set(node, "type", TypeType.INSTANCE);
        R.set(node, "declared", new StructType(node));
    }

    // ---------------------------------------------------------------------------------------------

    private void classDecl (ClassDeclarationNode node) {

        scope.declare(node.name, node);

        scope = new ClassScope(node, scope, classScopes, node);
        R.set(node, "scope", scope);

        final Scope classScope = scope;

        R.rule().using().by(r -> {
            // Ensure that the class name starts with a capital letter.
            if (!Character.isUpperCase(node.name.charAt(0)))
                r.errorFor("Class name must start with a capital letter.", node, node.attr("StartCapitalLetterCheck"));
        });

        R.rule().using(node.attr("ancestors")).by(r -> {
            ArrayList<DeclarationContext> ancestors = r.get(0);
            ClassType type = new ClassType(node.name);

            // Awaits for the ancestors to be resolved.
            ArrayList<DeclarationNode> attributes = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            for (DeclarationContext ancestor : ancestors) {
                ClassDeclarationNode ancestorNode = (ClassDeclarationNode) ancestor.declaration;
                for (DeclarationNode decl : ancestorNode.body) {
                    attributes.add(decl);
                    names.add(decl.name());
                }
            }

            Attribute[] dependencies = new Attribute[attributes.size()];
            for (int i = 0; i < attributes.size(); i++) {
                dependencies[i] = attributes.get(i).attr("type");
            }

            R.rule(node, "type").using(dependencies).by(rr -> {
                for (int i = 0; i < dependencies.length; i++) {
                    Object value = rr.get(i);
                    if (value instanceof SemanticError) {
                        rr.errorFor("Found errors in class body: " + node.name,
                                node, node.attr("type"));
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    int err = type.addKeys(names.get(i), rr.get(i), sb);
                    if (err == 1){
                        rr.errorFor("Cannot override variable : " + names.get(i) + "\n can only override methods.",
                                node, node.attr("Type"));
                    } else if (err == 2){
                        rr.errorFor("Cannot override variable : " + names.get(i) + " : ClassType " + type.hasField(names.get(i)) + " cannot be assigned to " + rr.get(i) +
                                "(" + sb.toString() + ")", node, node.attr("Type"));
                    } else if (err > 0){
                        rr.errorFor(sb.toString(), node, node.attr("Type"));
                    }
                }
                rr.set(0, type);
                ((ClassScope) classScope).setType(type);
            });
        });

        R.rule(node, "declared").using(node.attr("type")).by(r -> {
            ClassType type = (ClassType) r.get(0);
            r.set(0, type);
        });
        R.rule(node, "constructor")
                .by(r -> {
                    DeclarationContext constructor = classScope.lookup("<constructor>");
                    if (constructor == null) {
                        r.error("Missing constructor for class `" + node.name + "`.", node);
                    } else {
                        r.set(0, constructor);
                    }
                });

        R.rule(node, "ancestors")
                .by(r -> {
                    ArrayList<DeclarationContext> ancestors = new ArrayList<>();
                    ancestors.add(classScope.lookup(node.name));
                    if (node.parent != null) {
                        // Check if the parent class is declared.
                        DeclarationContext parent = classScope.lookup(node.parent);
                        if (parent == null) {
                            R.rule(node, "undeclaredAncestor").by(rr -> {
                               rr.error("Undeclared ancestor `" + node.parent + "`.", node);
                            });
                            r.set(0, ancestors);
                        } else {
                            // Check if the parent is a class.
                            if (!(parent.declaration instanceof ClassDeclarationNode)) {
                                R.rule(node, "ancestorIsNotAClass").by(rr -> {
                                    rr.error("Parent `" + node.parent + "` is not a class.", node);
                                });
                                r.set(0, ancestors);
                            } else {
                                // Check for cyclic inheritance.
                                DeclarationContext current = parent;
                                boolean cyclic = false;
                                String path = node.name + " <- ";
                                while (current != null && !cyclic) {
                                    ClassDeclarationNode parentClass = (ClassDeclarationNode) current.declaration;
                                    path += parentClass.name + " <- ";
                                    if (parentClass.name.equals(node.name)) {
                                        cyclic = true;
                                    } else {
                                        ancestors.add(current);
                                        current = classScope.lookup(parentClass.parent);
                                    }
                                }
                                if (!cyclic) {
                                    r.set(0, ancestors);
                                } else {
                                    R.rule(node, "cyclicAncestors").by(rr -> {
                                        rr.error("Cyclic inheritance detected", node);
                                    });
                                    r.set(0, ancestors);
                                }
                            }
                        }
                    } else {
                        r.set(0, ancestors);
                    }
                });

    }

    // endregion
    // =============================================================================================
    // region [Other Statements]
    // =============================================================================================

    private void ifStmt (IfNode node) {
        R.rule()
        .using(node.condition, "type")
        .by(r -> {
            Type type = r.get(0);
            if (!(type instanceof BoolType)) {
                r.error("If statement with a non-boolean condition of type: " + type,
                    node.condition);
            }
        });

        Attribute[] deps = getReturnsDependencies(list(node.trueStatement, node.falseStatement));
        R.rule(node, "returns")
        .using(deps)
        .by(r -> r.set(0, deps.length == 2 && Arrays.stream(deps).allMatch(r::get)));
    }

    // ---------------------------------------------------------------------------------------------

    private void whileStmt (WhileNode node) {
        R.rule()
        .using(node.condition, "type")
        .by(r -> {
            Type type = r.get(0);
            if (!(type instanceof BoolType)) {
                r.error("While statement with a non-boolean condition of type: " + type,
                    node.condition);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void returnStmt (ReturnNode node)
    {
        R.set(node, "returns", true);
        R.set(node, "threadIndex", threadIndex);

        FunDeclarationNode function = currentFunction();
        if (function == null) // top-level return
            return;

        if (node.expression == null)
            R.rule()
            .using(function.returnType, "value")
            .by(r -> {
               Type returnType = r.get(0);
               if (!(returnType instanceof VoidType))
                   r.error("Return without value in a function with a return type.", node);
            });
        else
            R.rule()
            .using(function.returnType.attr("value"), node.expression.attr("type"))
            .by(r -> {
                Type formal = r.get(0);
                
                // Special case: function of which return type is Unborn => return type must match the type of Unborn
                if (formal instanceof UnbornType)
                    formal = ((UnbornType) formal).componentType;

                Type actual = r.get(1);
                if (formal instanceof VoidType)
                    r.error("Return with value in a Void function.", node);
                else if (!isAssignableTo(actual, formal)) {
                    r.errorFor(format(
                        "Incompatible return type, expected %s but got %s", formal, actual),
                        node.expression);
                }
            });
    }

    // ---------------------------------------------------------------------------------------------

    private FunDeclarationNode currentFunction()
    {
        Scope scope = this.scope;
        while (scope != null) {
            SighNode node = scope.node;
            if (node instanceof FunDeclarationNode)
                return (FunDeclarationNode) node;
            scope = scope.parent;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private boolean isReturnContainer (SighNode node) {
        return node instanceof BlockNode
            || node instanceof IfNode
            || node instanceof ReturnNode;
    }

    // ---------------------------------------------------------------------------------------------

    /** Get the depedencies necessary to compute the "returns" attribute of the parent. */
    private Attribute[] getReturnsDependencies (List<? extends SighNode> children) {
        return children.stream()
            .filter(Objects::nonNull)
            .filter(this::isReturnContainer)
            .map(it -> it.attr("returns"))
            .toArray(Attribute[]::new);
    }

    // endregion
    // =============================================================================================
}