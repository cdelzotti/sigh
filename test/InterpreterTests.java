import norswap.autumn.AutumnTestFixture;
import norswap.autumn.Grammar;
import norswap.autumn.Grammar.rule;
import norswap.autumn.ParseResult;
import norswap.autumn.positions.LineMapString;
import norswap.sigh.SemanticAnalysis;
import norswap.sigh.SighGrammar;
import norswap.sigh.ast.SighNode;
import norswap.sigh.interpreter.Interpreter;
import norswap.sigh.interpreter.InterpreterException;
import norswap.sigh.interpreter.Null;
import norswap.uranium.Reactor;
import norswap.uranium.SemanticError;
import norswap.utils.IO;
import norswap.utils.TestFixture;
import norswap.utils.data.wrappers.Pair;
import norswap.utils.visitors.Walker;
import org.testng.annotations.Test;
import java.util.HashMap;
import java.util.Set;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;

public final class InterpreterTests extends TestFixture {

    // TODO peeling

    // ---------------------------------------------------------------------------------------------

    private final SighGrammar grammar = new SighGrammar();
    private final AutumnTestFixture autumnFixture = new AutumnTestFixture();

    {
        autumnFixture.runTwice = false;
        autumnFixture.bottomClass = this.getClass();
    }

    // ---------------------------------------------------------------------------------------------

    private Grammar.rule rule;

    // ---------------------------------------------------------------------------------------------

    private void check (String input, Object expectedReturn) {
        assertNotNull(rule, "You forgot to initialize the rule field.");
        check(rule, input, expectedReturn, null);
    }

    // ---------------------------------------------------------------------------------------------

    private void check (String input, Object expectedReturn, String expectedOutput) {
        assertNotNull(rule, "You forgot to initialize the rule field.");
        check(rule, input, expectedReturn, expectedOutput);
    }

    // ---------------------------------------------------------------------------------------------

    private void check (rule rule, String input, Object expectedReturn, String expectedOutput) {
        // TODO
        // (1) write proper parsing tests
        // (2) write some kind of automated runner, and use it here

        autumnFixture.rule = rule;
        ParseResult parseResult = autumnFixture.success(input);
        SighNode root = parseResult.topValue();

        Reactor reactor = new Reactor();
        Walker<SighNode> walker = SemanticAnalysis.createWalker(reactor);
        Interpreter interpreter = new Interpreter(reactor);
        walker.walk(root);
        reactor.run();
        Set<SemanticError> errors = reactor.errors();

        if (!errors.isEmpty()) {
            LineMapString map = new LineMapString("<test>", input);
            String report = reactor.reportErrors(it ->
                it.toString() + " (" + ((SighNode) it).span.startString(map) + ")");
            //            String tree = AttributeTreeFormatter.format(root, reactor,
            //                    new ReflectiveFieldWalker<>(SighNode.class, PRE_VISIT, POST_VISIT));
            //            System.err.println(tree);
            throw new AssertionError(report);
        }

        Pair<String, Object> result = IO.captureStdout(() -> interpreter.interpret(root));
        assertEquals(result.b, expectedReturn);
        if (expectedOutput != null) assertEquals(result.a, expectedOutput);
    }

    // ---------------------------------------------------------------------------------------------

    private void checkExpr (String input, Object expectedReturn, String expectedOutput) {
        rule = grammar.root;
        check("return " + input, expectedReturn, expectedOutput);
    }

    // ---------------------------------------------------------------------------------------------

    private void checkExpr (String input, Object expectedReturn) {
        rule = grammar.root;
        check("return " + input, expectedReturn);
    }

    // ---------------------------------------------------------------------------------------------

    private void checkThrows (String input, Class<? extends Throwable> expected) {
        assertThrows(expected, () -> check(input, null));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testLiteralsAndUnary () {
        checkExpr("42", 42L);
        checkExpr("42.0", 42.0d);
        checkExpr("\"hello\"", "hello");
        checkExpr("(42)", 42L);
        checkExpr("[1, 2, 3]", new Object[]{1L, 2L, 3L});
        checkExpr("true", true);
        checkExpr("false", false);
        checkExpr("null", Null.INSTANCE);
        checkExpr("!false", true);
        checkExpr("!true", false);
        checkExpr("!!true", true);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testNumericBinary () {
        checkExpr("1 + 2", 3L);
        checkExpr("2 - 1", 1L);
        checkExpr("2 * 3", 6L);
        checkExpr("2 / 3", 0L);
        checkExpr("3 / 2", 1L);
        checkExpr("2 % 3", 2L);
        checkExpr("3 % 2", 1L);

        checkExpr("1.0 + 2.0", 3.0d);
        checkExpr("2.0 - 1.0", 1.0d);
        checkExpr("2.0 * 3.0", 6.0d);
        checkExpr("2.0 / 3.0", 2d / 3d);
        checkExpr("3.0 / 2.0", 3d / 2d);
        checkExpr("2.0 % 3.0", 2.0d);
        checkExpr("3.0 % 2.0", 1.0d);

        checkExpr("1 + 2.0", 3.0d);
        checkExpr("2 - 1.0", 1.0d);
        checkExpr("2 * 3.0", 6.0d);
        checkExpr("2 / 3.0", 2d / 3d);
        checkExpr("3 / 2.0", 3d / 2d);
        checkExpr("2 % 3.0", 2.0d);
        checkExpr("3 % 2.0", 1.0d);

        checkExpr("1.0 + 2", 3.0d);
        checkExpr("2.0 - 1", 1.0d);
        checkExpr("2.0 * 3", 6.0d);
        checkExpr("2.0 / 3", 2d / 3d);
        checkExpr("3.0 / 2", 3d / 2d);
        checkExpr("2.0 % 3", 2.0d);
        checkExpr("3.0 % 2", 1.0d);

        checkExpr("2 * (4-1) * 4.0 / 6 % (2+1)", 1.0d);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testOtherBinary () {
        checkExpr("true  && true",  true);
        checkExpr("true  || true",  true);
        checkExpr("true  || false", true);
        checkExpr("false || true",  true);
        checkExpr("false && true",  false);
        checkExpr("true  && false", false);
        checkExpr("false && false", false);
        checkExpr("false || false", false);

        checkExpr("1 + \"a\"", "1a");
        checkExpr("\"a\" + 1", "a1");
        checkExpr("\"a\" + true", "atrue");

        checkExpr("1 == 1", true);
        checkExpr("1 == 2", false);
        checkExpr("1.0 == 1.0", true);
        checkExpr("1.0 == 2.0", false);
        checkExpr("true == true", true);
        checkExpr("false == false", true);
        checkExpr("true == false", false);
        checkExpr("1 == 1.0", true);
        checkExpr("[1] == [1]", false);

        checkExpr("1 != 1", false);
        checkExpr("1 != 2", true);
        checkExpr("1.0 != 1.0", false);
        checkExpr("1.0 != 2.0", true);
        checkExpr("true != true", false);
        checkExpr("false != false", false);
        checkExpr("true != false", true);
        checkExpr("1 != 1.0", false);

        checkExpr("\"hi\" != \"hi2\"", true);
        checkExpr("[1] != [1]", true);

         // test short circuit
        checkExpr("true || print(\"x\") == \"y\"", true, "");
        checkExpr("false && print(\"x\") == \"y\"", false, "");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testVarDecl () {
        check("var x: Int = 1; return x", 1L);
        check("var x: Float = 2.0; return x", 2d);

        check("var x: Int = 0; return x = 3", 3L);
        check("var x: String = \"0\"; return x = \"S\"", "S");

        // implicit conversions
        check("var x: Float = 1; x = 2; return x", 2.0d);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testRootAndBlock () {
        rule = grammar.root;
        check("return", null);
        check("return 1", 1L);
        check("return 1; return 2", 1L);

        check("print(\"a\")", null, "a\n");
        check("print(\"a\" + 1)", null, "a1\n");
        check("print(\"a\"); print(\"b\")", null, "a\nb\n");

        check("{ print(\"a\"); print(\"b\") }", null, "a\nb\n");

        check(
            "var x: Int = 1;" +
            "{ print(\"\" + x); var x: Int = 2; print(\"\" + x) }" +
            "print(\"\" + x)",
            null, "1\n2\n1\n");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testCalls () {
        check(
            "fun add (a: Int, b: Int): Int { return a + b } " +
                "return add(4, 7)",
            11L);

        HashMap<String, Object> point = new HashMap<>();
        point.put("x", 1L);
        point.put("y", 2L);

        check(
            "struct Point { var x: Int; var y: Int }" +
                "return $Point(1, 2)",
            point);

        check("var str: String = null; return print(str + 1)", "null1", "null1\n");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testArrayStructAccess () {
        checkExpr("[1][0]", 1L);
        checkExpr("[1.0][0]", 1d);
        checkExpr("[1, 2][1]", 2L);

        // TODO check that this fails (& maybe improve so that it generates a better message?)
        // or change to make it legal (introduce a top type, and make it a top type array if thre
        // is no inference context available)
        // checkExpr("[].length", 0L);
        checkExpr("[1].length", 1L);
        checkExpr("[1, 2].length", 2L);

        checkThrows("var array: Int[] = null; return array[0]", NullPointerException.class);
        checkThrows("var array: Int[] = null; return array.length", NullPointerException.class);

        check("var x: Int[] = [0, 1]; x[0] = 3; return x[0]", 3L);
        checkThrows("var x: Int[] = []; x[0] = 3; return x[0]",
            ArrayIndexOutOfBoundsException.class);
        checkThrows("var x: Int[] = null; x[0] = 3",
            NullPointerException.class);

        check(
            "struct P { var x: Int; var y: Int }" +
                "return $P(1, 2).y",
            2L);

        checkThrows(
            "struct P { var x: Int; var y: Int }" +
                "var p: P = null;" +
                "return p.y",
            NullPointerException.class);

        check(
            "struct P { var x: Int; var y: Int }" +
                "var p: P = $P(1, 2);" +
                "p.y = 42;" +
                "return p.y",
            42L);

        checkThrows(
            "struct P { var x: Int; var y: Int }" +
                "var p: P = null;" +
                "p.y = 42",
            NullPointerException.class);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testIfWhile () {
        check("if (true) return 1 else return 2", 1L);
        check("if (false) return 1 else return 2", 2L);
        check("if (false) return 1 else if (true) return 2 else return 3 ", 2L);
        check("if (false) return 1 else if (false) return 2 else return 3 ", 3L);

        check("var i: Int = 0; while (i < 3) { print(\"\" + i); i = i + 1 } ", null, "0\n1\n2\n");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testInference () {
        check("var array: Int[] = []", null);
        check("var array: String[] = []", null);
        check("fun use_array (array: Int[]) {} ; use_array([])", null);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testTypeAsValues () {
        check("struct S{} ; return \"\"+ S", "S");
        check("struct S{} ; var type: Type = S ; return \"\"+ type", "S");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testUnconditionalReturn()
    {
        check("fun f(): Int { if (true) return 1 else return 2 } ; return f()", 1L);
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testClasses(){
        // Basic class
        check("class Car {var speed : Int = 0 fun Car(){} fun accelerate(){speed = speed + 1}} var car : Car = Car() car.accelerate() print(\"\"+car.speed)",null, "1\n");
        // Class with inheritance and overriding
        check("class Car {var speed : Int = 0 fun Car(){} fun accelerate(){speed = speed + 1}} class Truck sonOf Car {fun Truck(){}  fun accelerate(){speed = speed + 2}} var car : Car = Truck() car.accelerate() print(\"\"+car.speed)",null, "2\n");
        // Class with inner classes
        check("class Car { fun Car(){}  class Engine {var speed : Int = 0 fun Engine(){} fun accelerate(){speed = speed + 1}} var engine : Engine = Engine() fun accelerate(){engine.accelerate()}} var car : Car = Car() car.accelerate() print(\"\"+car.engine.speed)",null, "1\n");
        // Duck typing
        check("class A { var a : Int = 0  fun A(){} fun increment(){a = a + 1}} class B { var b : Int = 0 var a : Int = 0 fun B(){} fun increment(){b = b + 1}} var a : A = B() a.increment() print(\"\"+a.a)",null, "0\n");
        // Method substitution
        check("class ClassOne { fun ClassOne() {} fun someFunc() : Int { return 0 } } class ClassTwo { fun ClassTwo() {} fun someFunc() : Int { return 1 } } var instance : ClassOne = ClassTwo() var value : Int = instance.someFunc() print(\"\"+value)",null, "1\n");
        // Use parent-defined variable
        check("class ClassOne { fun ClassOne() {} var a : Int = 0 } class ClassTwo sonOf ClassOne { fun ClassTwo() {} fun someFunc() : Int { return a } } var instance : ClassTwo = ClassTwo() var value : Int = instance.someFunc() print(\"\"+value)",null, "0\n");
        // Daddy calls
        check("class ClassOne {\n" +
                "    var a : Int = 0\n" +
                "    fun ClassOne() {}\n" +
                "\n" +
                "    fun setA(value : Int) {\n" +
                "        a = value\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "class ClassTwo sonOf ClassOne {\n" +
                "    fun ClassTwo() {}\n" +
                "\n" +
                "    fun setA(value : Int) {\n" +
                "        Daddy(value)\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "var instance : ClassTwo = ClassTwo()\n" +
                "instance.setA(12)\n" +
                "print(\"\"+instance.a)", null, "12\n");
    }
    // ---------------------------------------------------------------------------------------------

    @Test public void testSugar(){
        // Rapid increment
        check("var i : Int = 0  i++ print(\"\"+i)",null, "1\n");
        // Rapid decrement
        check("var i : Int = 1  i-- print(\"\"+i)",null, "0\n");
        // Rapid addition
        check("var i : Int = 0  i += 12 print(\"\"+i)",null, "12\n");
        // Rapid subtraction
        check("var i : Int = 12  i -= 12 print(\"\"+i)",null, "0\n");
        // Rapid multiplication
        check("var i : Int = 2  i *= 12 print(\"\"+i)",null, "24\n");
        // Rapid division
        check("var i : Int = 12  i /= 12 print(\"\"+i)",null, "1\n");
        // siblingsOf working with heritage
        check("class A { fun A(){} } class B sonOf A { fun B(){}} var a : Auto = A() var b : Auto = B() if (a siblingsOf b) print(\"working\") else print(\"crashing\")",null, "working\n");
        // siblingsOf working with duck typing
        check("class A { var i : Int = 12  fun A(){} } class B { var i : Int = 23 fun B(){}} var a : Auto = A() var b : Auto = B() if (a siblingsOf b) print(\"working\") else print(\"crashing\")",null, "working\n");
        // ciblingOf not working
        check("class A { var k : Int = 12  fun A(){} } class B { var i : Int = 23 fun B(){}} var a : Auto = A() var b : Auto = B() if (a siblingsOf b) print(\"working\") else print(\"crashing\")",null, "crashing\n");

    }

    // ---------------------------------------------------------------------------------------------


    @Test public void testAsync() {
        // Assignment
        check("fun async(): Unborn<Int> {var asyncVar: Int = 10 return asyncVar} fun sync(): Int {var syncVar: Int = 20 return syncVar} async() var resultAsync: Int = 0 born(async, resultAsync) var resultSync: Int = sync() print(\"\" + resultAsync) print(\"\" + resultSync)", null, "10\n20\n");

        // Void async
        check("fun async(): Unborn<Void> {print(\"hello\")} async() born(async)", null, "hello\n");
        
        // Parameters
        check("fun async(param: String): Unborn<Void> {print(\"hello \" + param)} async(\"world\") born(async)", null, "hello world\n");

        // Unborn with no born
        // Works because missing born statements are automatically added to prevent async thread from running indefinitely
        check("fun async(param: String): Unborn<Void> {print(\"hello \" + param)} async(\"world\")", null, "hello world\n");

        // Born with no unborn
        // Throws an InterpreterException because this error can only be checked at runtime
        checkThrows("fun async(param: String): Unborn<Void> {print(\"hello \" + param)} born(async)", InterpreterException.class);
    }

    // NOTE(norswap): Not incredibly complete, but should cover the basics.
}
