import norswap.autumn.AutumnTestFixture;
import norswap.autumn.Grammar;
import norswap.autumn.Grammar.rule;
import norswap.autumn.ParseResult;
import norswap.autumn.positions.LineMapString;
import norswap.sigh.SemanticAnalysis;
import norswap.sigh.SighGrammar;
import norswap.sigh.ast.SighNode;
import norswap.sigh.interpreter.Interpreter;
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

public final class IntegrationTests extends TestFixture {

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

    // ---------------------------------- Test Classes ----------------------------------------------

    @Test
    public void testBasicMethodInheritance () {
        rule = grammar.root;
        check("class FatherClass {\n" +
                "    fun FatherClass(){}\n" +
                "    \n" +
                "    fun printHello(){\n" +
                "        print(\"Hello\")\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "class MyClass sonOf FatherClass { \n" +
                "    fun MyClass (){} \n" +
                "}\n" +
                "\n" +
                "\n" +
                "var instance : MyClass = MyClass()\n" +
                "instance.printHello()",null, "Hello\n");
    }

    @Test
    public void testMethodOverriding() {
        check("class FatherClass {\n" +
                "    fun FatherClass(){}\n" +
                "    \n" +
                "    fun printHello(){\n" +
                "        print(\"Hello\")\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "class MyClass sonOf FatherClass { \n" +
                "    fun MyClass (){}\n" +
                "    \n" +
                "    fun printHello(){\n" +
                "        print(\"Hello World !\")\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "\n" +
                "var instance : MyClass = MyClass() // Create instance of MyClass\n" +
                "instance.printHello() // Call inherited method", null, "Hello World !\n");
    }

    @Test
    public void testDuckTyping (){
        check("class FatherClass {\n" +
                "    var SomeVar : Int = 0\n" +
                "\n" +
                "    fun FatherClass(){}\n" +
                "    \n" +
                "    fun printHello(){\n" +
                "        print(\"Hello\")\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "class MyClass {\n" +
                "    var SomeVar : Int = 12\n" +
                "    var someOtherVar : String = \"A nice string\"\n" +
                "\n" +
                "    fun MyClass (){}\n" +
                "    \n" +
                "    fun printHello(){\n" +
                "        print(\"Hello World !\")\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "// Correct as MyClass implements every member\n" +
                "// of FatherClass with the same types\n" +
                "var instance : FatherClass = MyClass()\n" +
                "print(\"\"+instance.SomeVar)", null, "12\n");
    }

    @Test
    public void testImbricatedClasses () {
        check("class RootClass {\n" +
                "    class ImbricatedClass{\n" +
                "        var a : Int = 0\n" +
                "\n" +
                "        fun ImbricatedClass(initialVal : Int){\n" +
                "            a = initialVal\n" +
                "        }\n" +
                "    }\n" +
                "\n" +
                "    var imbricatedClass : ImbricatedClass = ImbricatedClass(0)\n" +
                "\n" +
                "    fun RootClass(init : Int){\n" +
                "        imbricatedClass = ImbricatedClass(init)\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "var instance : RootClass = RootClass(456)\n" +
                "print(\"\"+instance.imbricatedClass.a)", null, "456\n");
    }

    @Test
    public void testPerClassConstructor(){
        check("class ClassOne {\n" +
                    "var name : String = \" ClassOne \"\n" +
                    "fun ClassOne () {}\n" +
                    "fun printName () {\n" +
                        "print ( name ) ;\n" +
                    "}\n" +
                "}\n" +
                "class ClassTwo sonOf ClassOne {\n" +
                    "fun ClassTwo ( newName : String ) {\n" +
                        "name = newName\n" +
                    "}\n" +
                "}\n" +
                "var instance : ClassOne = ClassTwo ( \" ClassTwo \" )\n" +
                "instance.printName ()",null, " ClassTwo \n");
    }

    @Test
    public void testDaddyCall(){
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

    // ------------------------------------ Mixed features -----------------------------------

    @Test
    public void testFunWithArrays(){
        check("class GenericClass {\n" +
                "    fun GenericClass(){\n" +
                "    }\n" +
                "    fun getArea() : Int {\n" +
                "        return 0\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "class SquareClass {\n" +
                "    var size: Int = 0\n" +
                "    fun SquareClass(squareSize: Int){\n" +
                "        size = squareSize\n" +
                "    }\n" +
                "\n" +
                "    fun getArea(): Int {\n" +
                "        return size * size\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "class RectangleClass {\n" +
                "    var width: Int = 0\n" +
                "    var height: Int = 0\n" +
                "    fun RectangleClass(rectangleWidth: Int, rectangleHeight: Int){\n" +
                "        width = rectangleWidth\n" +
                "        height = rectangleHeight\n" +
                "    }\n" +
                "\n" +
                "    fun getArea(): Int {\n" +
                "        return width * height\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "var classArray: GenericClass[] = [GenericClass(),GenericClass(),GenericClass(),GenericClass()]\n" +
                "// Fill Array\n" +
                "var i : Int = 0\n" +
                "while (i < classArray.length) {\n" +
                "    if (i % 2 == 0) {\n" +
                "        classArray[i] = SquareClass(i)\n" +
                "    } else {\n" +
                "        classArray[i] = RectangleClass(i, i*2)\n" +
                "    }i++\n" +
                "}\n" +
                "// Print content\n" +
                "i = 0\n" +
                "while (i < classArray.length) {\n" +
                "    if (classArray[i] siblingsOf RectangleClass) {\n" +
                "        print(\"Rectangle of area : \" + classArray[i].getArea())\n" +
                "    } else {\n" +
                "        print(\"Square of area : \" + classArray[i].getArea())\n" +
                "    }\n" +
                "    i++\n" +
                "}", null, "Square of area : 0\n" +
                "Rectangle of area : 2\n" +
                "Square of area : 4\n" +
                "Rectangle of area : 18\n");
    }

}
