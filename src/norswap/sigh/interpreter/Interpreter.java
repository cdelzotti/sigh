package norswap.sigh.interpreter;

import norswap.sigh.ast.*;
import norswap.sigh.scopes.*;
import norswap.sigh.types.*;
import norswap.uranium.Reactor;
import norswap.utils.Util;
import norswap.utils.exceptions.Exceptions;
import norswap.utils.exceptions.NoStackException;
import norswap.utils.visitors.ValuedVisitor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static norswap.utils.Util.cast;
import static norswap.utils.Vanilla.coIterate;
import static norswap.utils.Vanilla.map;

/**
 * Implements a simple but inefficient interpreter for Sigh.
 *
 * <h2>Limitations</h2>
 * <ul>
 *     <li>The compiled code currently doesn't support closures (using variables in functions that
 *     are declared in some surroudning scopes outside the function). The top scope is supported.
 *     </li>
 * </ul>
 *
 * <p>Runtime value representation:
 * <ul>
 *     <li>{@code Int}, {@code Float}, {@code Bool}: {@link Long}, {@link Double}, {@link Boolean}</li>
 *     <li>{@code String}: {@link String}</li>
 *     <li>{@code null}: {@link Null#INSTANCE}</li>
 *     <li>Arrays: {@code Object[]}</li>
 *     <li>Structs: {@code HashMap<String, Object>}</li>
 *     <li>Functions: the corresponding {@link DeclarationNode} ({@link FunDeclarationNode} or
 *     {@link SyntheticDeclarationNode}), excepted structure constructors, which are
 *     represented by {@link Constructor}</li>
 *     <li>Types: the corresponding {@link StructDeclarationNode}</li>
 * </ul>
 */
public final class Interpreter
{
    // ---------------------------------------------------------------------------------------------

    private final ValuedVisitor<SighNode, Object> visitor = new ValuedVisitor<>();
    private final Reactor reactor;
    private HashMap<Integer, ScopeStorage> storage = new HashMap<>();
    private RootScope rootScope;
    private ScopeStorage rootStorage;
    private HashMap<String, Thread> threadPool = new HashMap<>();
    private HashMap<Integer, Object> returnValues = new HashMap<>();

    // ---------------------------------------------------------------------------------------------

    public Interpreter (Reactor reactor) {
        this.reactor = reactor;

        // expressions
        visitor.register(IntLiteralNode.class,           this::intLiteral);
        visitor.register(FloatLiteralNode.class,         this::floatLiteral);
        visitor.register(StringLiteralNode.class,        this::stringLiteral);
        visitor.register(ReferenceNode.class,            this::reference);
        visitor.register(ConstructorNode.class,          this::constructor);
        visitor.register(ArrayLiteralNode.class,         this::arrayLiteral);
        visitor.register(ParenthesizedNode.class,        this::parenthesized);
        visitor.register(FieldAccessNode.class,          this::fieldAccess);
        visitor.register(ArrayAccessNode.class,          this::arrayAccess);
        visitor.register(FunCallNode.class,              this::funCall);
        visitor.register(DaddyCallNode.class,            this::daddyCall);
        visitor.register(UnaryExpressionNode.class,      this::unaryExpression);
        visitor.register(BinaryExpressionNode.class,     this::binaryExpression);
        visitor.register(AssignmentNode.class,           this::assignment);

        // statement groups & declarations
        visitor.register(RootNode.class,                 this::root);
        visitor.register(BlockNode.class,                this::block);
        visitor.register(VarDeclarationNode.class,       this::varDecl);
        // no need to visitor other declarations! (use fallback)

        // statements
        visitor.register(ExpressionStatementNode.class,  this::expressionStmt);
        visitor.register(IfNode.class,                   this::ifStmt);
        visitor.register(WhileNode.class,                this::whileStmt);
        visitor.register(ReturnNode.class,               this::returnStmt);
        visitor.register(BornNode.class,                 this::bornStmt);

        visitor.registerFallback(node -> null);
    }

    // ---------------------------------------------------------------------------------------------

    public Object interpret (SighNode root) {
        try {
            return run(root);
        } catch (PassthroughException | BornNodeException e) {
            throw Exceptions.runtime(e.getCause());
        }
    }

    // ---------------------------------------------------------------------------------------------

    private Object run (SighNode node) {
        try {
            return visitor.apply(node);
        } catch (InterpreterException | Return | PassthroughException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InterpreterException("exception while executing " + node, e);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Used to implement the control flow of the return statement.
     */
    private static class Return extends NoStackException {
        final Object value;
        private Return (Object value) {
            this.value = value;
        }
    }

    // ---------------------------------------------------------------------------------------------

    private <T> T get(SighNode node) {
        return cast(run(node));
    }

    // ---------------------------------------------------------------------------------------------

    private Long intLiteral (IntLiteralNode node) {
        return node.value;
    }

    private Double floatLiteral (FloatLiteralNode node) {
        return node.value;
    }

    private String stringLiteral (StringLiteralNode node) {
        return node.value;
    }

    // ---------------------------------------------------------------------------------------------

    private Object parenthesized (ParenthesizedNode node) {
        return get(node.expression);
    }

    // ---------------------------------------------------------------------------------------------

    private Object[] arrayLiteral (ArrayLiteralNode node) {
        return map(node.components, new Object[0], visitor);
    }

    // ---------------------------------------------------------------------------------------------

    private Object binaryExpression (BinaryExpressionNode node)
    {
        Type leftType  = reactor.get(node.left, "type");
        Type rightType = reactor.get(node.right, "type");

        // Cases where both operands should not be evaluated.
        switch (node.operator) {
            case OR:  return booleanOp(node, false);
            case AND: return booleanOp(node, true);
        }

        Object left  = get(node.left);
        Object right = get(node.right);

        if (node.operator == BinaryOperator.CIBLINGS){
            ClassType leftClass;
            ClassType rightClass;
            if (right instanceof ClassDeclarationNode){
                rightClass = reactor.get(right, "type");
                // return ((ClassInstance) left).type().canBeAssignedWith((ClassType) right, new StringBuilder());
            } else {
                rightClass = ((ClassInstance) right).type();
            }
            if (left instanceof ClassDeclarationNode) {
                leftClass = reactor.get(left, "type");
            } else {
                leftClass = ((ClassInstance) left).type();
            }
            return leftClass.canBeAssignedWith(rightClass, new StringBuilder());
        }

        if (node.operator == BinaryOperator.ADD
                && (leftType instanceof StringType || rightType instanceof StringType))
            return convertToString(left) + convertToString(right);

        boolean floating = leftType instanceof FloatType || rightType instanceof FloatType;
        boolean numeric  = floating || leftType instanceof IntType;

        if (numeric)
            return numericOp(node, floating, (Number) left, (Number) right);

        switch (node.operator) {
            case EQUALITY:
                return  leftType.isPrimitive() ? left.equals(right) : left == right;
            case NOT_EQUALS:
                return  leftType.isPrimitive() ? !left.equals(right) : left != right;
        }

        throw new Error("should not reach here");
    }

    // ---------------------------------------------------------------------------------------------

    private boolean booleanOp (BinaryExpressionNode node, boolean isAnd)
    {
        boolean left = get(node.left);
        return isAnd
                ? left && (boolean) get(node.right)
                : left || (boolean) get(node.right);
    }

    // ---------------------------------------------------------------------------------------------

    private Object numericOp
            (BinaryExpressionNode node, boolean floating, Number left, Number right)
    {
        long ileft, iright;
        double fleft, fright;

        if (floating) {
            fleft  = left.doubleValue();
            fright = right.doubleValue();
            ileft = iright = 0;
        } else {
            ileft  = left.longValue();
            iright = right.longValue();
            fleft = fright = 0;
        }

        Object result;
        if (floating)
            switch (node.operator) {
                case MULTIPLY:      return fleft *  fright;
                case DIVIDE:        return fleft /  fright;
                case REMAINDER:     return fleft %  fright;
                case ADD:           return fleft +  fright;
                case SUBTRACT:      return fleft -  fright;
                case GREATER:       return fleft >  fright;
                case LOWER:         return fleft <  fright;
                case GREATER_EQUAL: return fleft >= fright;
                case LOWER_EQUAL:   return fleft <= fright;
                case EQUALITY:      return fleft == fright;
                case NOT_EQUALS:    return fleft != fright;
                default:
                    throw new Error("should not reach here");
            }
        else
            switch (node.operator) {
                case MULTIPLY:      return ileft *  iright;
                case DIVIDE:        return ileft /  iright;
                case REMAINDER:     return ileft %  iright;
                case ADD:           return ileft +  iright;
                case SUBTRACT:      return ileft -  iright;
                case GREATER:       return ileft >  iright;
                case LOWER:         return ileft <  iright;
                case GREATER_EQUAL: return ileft >= iright;
                case LOWER_EQUAL:   return ileft <= iright;
                case EQUALITY:      return ileft == iright;
                case NOT_EQUALS:    return ileft != iright;
                default:
                    throw new Error("should not reach here");
            }
    }

    // ---------------------------------------------------------------------------------------------

    public Object assignment (AssignmentNode node)
    {
        if (node.left instanceof ReferenceNode) {
            Scope scope = reactor.get(node.left, "scope");
            String name = ((ReferenceNode) node.left).name;
            Object rvalue = get(node.right);
            assign(scope, name, rvalue, reactor.get(node, "type"), reactor.get(node, "threadIndex"));
            return rvalue;
        }

        if (node.left instanceof ArrayAccessNode) {
            ArrayAccessNode arrayAccess = (ArrayAccessNode) node.left;
            Object[] array = getNonNullArray(arrayAccess.array);
            int index = getIndex(arrayAccess.index);
            try {
                return array[index] = get(node.right);
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new PassthroughException(e);
            }
        }

        if (node.left instanceof FieldAccessNode) {
            FieldAccessNode fieldAccess = (FieldAccessNode) node.left;
            Object object = get(fieldAccess.stem);
            // If it is a Class Instance, extract fields to handle it as a normal struct
            if (object instanceof ClassInstance) {
                object = ((ClassInstance) object).fields();
            }
            if (object == Null.INSTANCE)
                throw new PassthroughException(
                    new NullPointerException("accessing field of null object"));
            Map<String, Object> struct = cast(object);
            Object right = get(node.right);
            struct.put(fieldAccess.fieldName, right);
            return right;
        }

        throw new Error("should not reach here");
    }

    // ---------------------------------------------------------------------------------------------

    private int getIndex (ExpressionNode node)
    {
        long index = get(node);
        if (index < 0)
            throw new ArrayIndexOutOfBoundsException("Negative index: " + index);
        if (index >= Integer.MAX_VALUE - 1)
            throw new ArrayIndexOutOfBoundsException("Index exceeds max array index (2ˆ31 - 2): " + index);
        return (int) index;
    }

    // ---------------------------------------------------------------------------------------------

    private Object[] getNonNullArray (ExpressionNode node)
    {
        Object object = get(node);
        if (object == Null.INSTANCE)
            throw new PassthroughException(new NullPointerException("indexing null array"));
        return (Object[]) object;
    }

    // ---------------------------------------------------------------------------------------------

    private Object unaryExpression (UnaryExpressionNode node)
    {
        // there is only NOT
        assert node.operator == UnaryOperator.NOT;
        return ! (boolean) get(node.operand);
    }

    // ---------------------------------------------------------------------------------------------

    private Object arrayAccess (ArrayAccessNode node)
    {
        Object[] array = getNonNullArray(node.array);
        try {
            return array[getIndex(node.index)];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new PassthroughException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private Object root (RootNode node)
    {
        assert storage.get(0) == null;
        rootScope = reactor.get(node, "scope");
        storage.put(0, new ScopeStorage(rootScope, null));
        rootStorage = storage.get(0);
        storage.get(0).initRoot(rootScope);

        try {
            node.statements.forEach(this::run);
            // Join all threads if the user forgot to born some async functions
            for (Thread thread : threadPool.values()) {
                try {
                        thread.join();
                } catch (Exception e) {
                    
                }
            }
        } catch (Return r) {
            return r.value;
            // allow returning from the main script
        } finally {
            storage = null;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Void block (BlockNode node) {
        Scope scope = reactor.get(node, "scope");
        int threadIndex = reactor.get(node, "threadIndex");
        storage.put(threadIndex, new ScopeStorage(scope, storage.get(threadIndex)));
        node.statements.forEach(this::run);
        assert (storage != null);
        assert (storage.get(threadIndex).parent != null);
        storage.put(threadIndex, storage.get(threadIndex).parent);
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Constructor constructor (ConstructorNode node) {
        // guaranteed safe by semantic analysis
        return new Constructor(get(node.ref));
    }

    // ---------------------------------------------------------------------------------------------

    private Object expressionStmt (ExpressionStatementNode node) {
        get(node.expression);
        return null;  // discard value
    }

    // ---------------------------------------------------------------------------------------------

    private Object fieldAccess (FieldAccessNode node)
    {
        Object stem = get(node.stem);
        if (stem == Null.INSTANCE)
            throw new PassthroughException(
                new NullPointerException("accessing field of null object"));
        // If it is a Class Instance, extract fields to handle it as a Struct
        if (stem instanceof ClassInstance)
            stem = ((ClassInstance) stem).fields();
        return stem instanceof Map
                ? Util.<Map<String, Object>>cast(stem).get(node.fieldName)
                : (long) ((Object[]) stem).length; // only field on arrays
    }

    // ---------------------------------------------------------------------------------------------

    private Object funCall (FunCallNode node)
    {
        Object decl = get(node.function);
        node.arguments.forEach(this::run);
        Object[] args = map(node.arguments, new Object[0], visitor);
        int threadIndex = reactor.get(node, "threadIndex");

        if (decl == Null.INSTANCE)
            throw new PassthroughException(new NullPointerException("calling a null function"));

        if (decl instanceof SyntheticDeclarationNode)
            return builtin(((SyntheticDeclarationNode) decl).name(), args);

        if (decl instanceof Constructor)
            return buildStruct(((Constructor) decl).declaration, args);

        ScopeStorage oldStorage = storage.get(threadIndex);
//        Scope scope = !(decl instanceof ClassDeclarationNode) ?  reactor.get(decl, "scope") : reactor.get(((Scope)reactor.get(decl, "scope")).lookup("<constructor>").declaration, "scope");
        Scope scope = !(node.function instanceof FieldAccessNode) ?reactor.get(decl, "scope") : ((ClassInstance) get(((FieldAccessNode) node.function).stem)).scope();
        storage.put(threadIndex, new ScopeStorage(scope, storage.get(threadIndex)));

        boolean async = false;
        FunDeclarationNode funDecl;
        ClassInstance returnValue;
        // Turns a function field access into a classical function call
        if (node.function instanceof FieldAccessNode) {
            ClassInstance classInstance = (ClassInstance) get(((FieldAccessNode) node.function).stem);
            ClassScope classScope = classInstance.scope();
            funDecl = (FunDeclarationNode) classScope.lookup(((FieldAccessNode) node.function).fieldName).declaration;
            returnValue = classInstance;
            // Restore the class scope
            for (String key : classInstance.fields().keySet()){
                storage.get(threadIndex).set(scope, key, classInstance.get_field(key));
            }
            // Create a functionScope for parameters
            Scope functionScope = reactor.get(funDecl, "scope");
            storage.put(threadIndex, new ScopeStorage(functionScope, storage.get(threadIndex)));
            // Register the parameter in the function scope
            coIterate(args, funDecl.parameters,
                    (arg, param) -> storage.get(threadIndex).set(functionScope, param.name, arg));
        } else if (decl instanceof ClassDeclarationNode) {
            // Function to execute is the constructor, retrieve the declaration
            funDecl = (FunDeclarationNode) scope.lookup("<constructor>").declaration;
            // Add constructor scope on top of the class scope
            Scope constructorScope = reactor.get(funDecl, "scope");
            storage.put(threadIndex, new ScopeStorage(constructorScope, storage.get(threadIndex)));
            // Must build the instance first
            ClassType type = (ClassType) reactor.get(decl, "type");
            ClassInstance instance = new ClassInstance((ClassScope) scope, type);
            HashMap<String, Type> innerTypes = type.getVariables();
            for (String key : innerTypes.keySet()) {
                DeclarationNode declNode = scope.lookup(key).declaration;
                // Register class variable in the class scope
                if (declNode instanceof VarDeclarationNode) {
                    instance.set_field(key, get(((VarDeclarationNode) declNode).initializer));
                    storage.get(threadIndex).set(scope, key, instance.get_field(key));
                }
            }
            // Register the parameter in the constructor scope
            coIterate(args, funDecl.parameters,
                    (arg, param) -> storage.get(threadIndex).set(constructorScope, param.name, arg));
            returnValue = instance;
        } else {
            // Normal function call
            funDecl = (FunDeclarationNode) decl;
            async = funDecl.returnType instanceof UnbornTypeNode;
            returnValue = null;
            int newThreadIndex = reactor.get(funDecl, "threadIndex");

            if (async) {
                storage.put(newThreadIndex, new ScopeStorage(scope, storage.get(threadIndex)));
            }
            coIterate(args, funDecl.parameters,
                (arg, param) -> storage.get(newThreadIndex).set(scope, param.name, arg));
        }

        try {
            if (async) {
                Thread newThread = new Thread(() -> {
                    get(funDecl.block);
                });

                // Add the thread to the thread pool
                threadPool.put(funDecl.name, newThread);
                newThread.start();
            } else {
                get(funDecl.block);
            }
        } catch (Return r) {
            return r.value;
        } finally {
            if (returnValue != null){
                // Find the class scope storage
                boolean found = false; // Use a boolean to avoid using break, some dogmatic people don't like breaks
                while (storage.get(threadIndex) != oldStorage && !found) {
                    if (storage.get(threadIndex).scope instanceof ClassScope) {
                        returnValue.refresh(storage.get(threadIndex));
                        found = true;
                    } else {
                        storage.put(threadIndex, storage.get(threadIndex).parent);
                    }
                }
            }
            storage.put(threadIndex, oldStorage);
            // If it is a field function access, set return value to null to allow the function to return null
            if (node.function instanceof FieldAccessNode)
                returnValue = null;
        }
        return returnValue;
    }

    // ---------------------------------------------------------------------------------------------

    private Object daddyCall (DaddyCallNode node)
    {
        MethodDeclarationNode funDecl = reactor.get(node, "parent");
        node.arguments.forEach(this::run);
        Object[] args = map(node.arguments, new Object[0], visitor);
        int threadIndex = reactor.get(node, "threadIndex");

        ScopeStorage oldStorage = storage.get(threadIndex);
        Scope scope = reactor.get(funDecl, "scope");
        storage.put(threadIndex, new ScopeStorage(scope, storage.get(threadIndex)));

        coIterate(args, funDecl.parameters,
                (arg, param) -> storage.get(threadIndex).set(scope, param.name, arg));

        try {
            get(funDecl.block);
        } catch (Return r) {
            return r.value;
        } finally {
            // Restore scope
            storage.put(threadIndex, oldStorage);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Object builtin (String name, Object[] args)
    {
        assert name.equals("print"); // only one at the moment
        String out = convertToString(args[0]);
        System.out.println(out);
        return out;
    }

    // ---------------------------------------------------------------------------------------------

    private String convertToString (Object arg)
    {
        if (arg == Null.INSTANCE)
            return "null";
        else if (arg instanceof Object[])
            return Arrays.deepToString((Object[]) arg);
        else if (arg instanceof FunDeclarationNode)
            return ((FunDeclarationNode) arg).name;
        else if (arg instanceof StructDeclarationNode)
            return ((StructDeclarationNode) arg).name;
        else if (arg instanceof Constructor)
            return "$" + ((Constructor) arg).declaration.name;
        else
            return arg.toString();
    }

    // ---------------------------------------------------------------------------------------------

    private HashMap<String, Object> buildStruct (StructDeclarationNode node, Object[] args)
    {
        HashMap<String, Object> struct = new HashMap<>();
        for (int i = 0; i < node.fields.size(); ++i)
            struct.put(node.fields.get(i).name, args[i]);
        return struct;
    }

    // ---------------------------------------------------------------------------------------------

    private Void ifStmt (IfNode node)
    {
        if ((boolean) get(node.condition))
            get(node.trueStatement);
        else if (node.falseStatement != null)
            get(node.falseStatement);
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Void whileStmt (WhileNode node)
    {
        while ((boolean) get(node.condition))
            get(node.body);
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Object reference (ReferenceNode node)
    {
        Scope scope = reactor.get(node, "scope");
        DeclarationNode decl = reactor.get(node, "decl");
        int threadIndex = reactor.get(node, "threadIndex");

        if (decl instanceof VarDeclarationNode
        || decl instanceof ParameterNode
        || decl instanceof SyntheticDeclarationNode
                && ((SyntheticDeclarationNode) decl).kind() == DeclarationKind.VARIABLE)
            return scope == rootScope
                ? rootStorage.get(scope, node.name)
                : storage.get(threadIndex).get(scope, node.name);

        return decl; // structure or function
    }

    // ---------------------------------------------------------------------------------------------

    private Void returnStmt (ReturnNode node) {
        int threadIndex = reactor.get(node, "threadIndex");
        if (threadIndex == 0) {
            // Normal functions
            throw new Return(node.expression == null ? null : get(node.expression));
        } else {
            // Async functions
            returnValues.put(threadIndex, node.expression == null ? null : get(node.expression));
            return null;
        }
        
    }

    // ---------------------------------------------------------------------------------------------

    private Void bornStmt (BornNode node) {
        try {
            Thread thread = threadPool.get(node.function.name);

            if (thread == null) {
                throw new BornNodeException("exception while executing born node " + node, new NullPointerException("Please call the async function before trying to born it."));
            }

            thread.join();
            if (!(node.variable == null)) {
                Scope scope = reactor.get(node, "scope");
                VarDeclarationNode varDecl = (VarDeclarationNode) scope.lookup(node.variable.name).declaration;
                FunDeclarationNode funDecl = (FunDeclarationNode) scope.lookup(node.function.name).declaration;
                int threadIndex = reactor.get(funDecl, "threadIndex");
                int nodeThreadIndex = reactor.get(node, "threadIndex");
                assign(scope, node.variable.name, returnValues.get(threadIndex), reactor.get(varDecl, "type"), nodeThreadIndex);
            }
        } catch (InterruptedException e) {
        }
        return null;
    }
        
    // ---------------------------------------------------------------------------------------------

    private Void varDecl (VarDeclarationNode node)
    {
        Scope scope = reactor.get(node, "scope");
        int threadIndex = reactor.get(node, "threadIndex");
        assign(scope, node.name, get(node.initializer), reactor.get(node, "type"), threadIndex);
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private void assign (Scope scope, String name, Object value, Type targetType, int threadIndex)
    {
        if (value instanceof Long && targetType instanceof FloatType)
            value = ((Long) value).doubleValue();
        storage.get(threadIndex).set(scope, name, value);
    }

    // ---------------------------------------------------------------------------------------------
}
