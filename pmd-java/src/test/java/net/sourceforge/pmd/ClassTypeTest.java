import static org.junit.Assert.assertEquals;

import net.sourceforge.pmd.lang.java.typeresolution.typedefinition.JavaTypeDefinition;
import net.sourceforge.pmd.typeresolution.testdata.SubTypeUsage;
import org.junit.Assert;
import org.junit.Test;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.JavaParsingHelper;
import net.sourceforge.pmd.lang.java.ast.ASTAllocationExpression;
import net.sourceforge.pmd.lang.java.ast.TypeNode;
import net.sourceforge.pmd.lang.java.typeresolution.ClassTypeResolver;
import net.sourceforge.pmd.typeresolution.testdata.AnonymousExtendingObject;
public class ClassTypeTest {
    private final JavaParsingHelper java8 = JavaParsingHelper.WITH_PROCESSING.withDefaultVersion("1.8");
    @Test
    public void testClassNameExists() {
        ClassTypeResolver classTypeResolver = new ClassTypeResolver();
        assertEquals(true, classTypeResolver.classNameExists("java.lang.System"));
        assertEquals(false, classTypeResolver.classNameExists("im.sure.that.this.does.not.Exist"));
        assertEquals(true, classTypeResolver.classNameExists("java.awt.List"));
    }
    @Test
    public void testAnonymousExtendingObject() throws Exception {
        Node acu = java8.parseClass(AnonymousExtendingObject.class);
        ASTAllocationExpression allocationExpression = acu.getFirstDescendantOfType(ASTAllocationExpression.class);
        TypeNode child = (TypeNode) allocationExpression.getChild(0);
        Assert.assertTrue(Object.class.isAssignableFrom(child.getType()));
    }
    @Test
    public void primitive() {
        JavaTypeDefinition typeDef = JavaTypeDefinition.forClass(int.class);
        Assert.assertTrue(typeDef.isPrimitive());
        Assert.assertFalse(typeDef.isClassOrInterface());
    }
    @Test
    public void testMethodOverrides() throws Exception {
        java8.parseClass(SubTypeUsage.class);
    }
}
