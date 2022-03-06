/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.symbols.internal.asm;

import static net.sourceforge.pmd.lang.java.symbols.internal.asm.ExecutableStub.CtorStub;
import static net.sourceforge.pmd.lang.java.symbols.internal.asm.ExecutableStub.MethodStub;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JConstructorSymbol;
import net.sourceforge.pmd.lang.java.symbols.JExecutableSymbol;
import net.sourceforge.pmd.lang.java.symbols.JFieldSymbol;
import net.sourceforge.pmd.lang.java.symbols.JMethodSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeParameterOwnerSymbol;
import net.sourceforge.pmd.lang.java.symbols.internal.SymbolEquality;
import net.sourceforge.pmd.lang.java.symbols.internal.SymbolToStrings;
import net.sourceforge.pmd.lang.java.symbols.internal.asm.GenericSigBase.LazyClassSignature;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JTypeVar;
import net.sourceforge.pmd.lang.java.types.LexicalScope;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeSystem;


final class ClassStub implements JClassSymbol, AsmStub {

    static final int UNKNOWN_ARITY = 0;

    private final AsmSymbolResolver resolver;
    private final Loader loader;

    private final Names names;

    // all the following are lazy and depend on the parse lock

    private int accessFlags;

    private EnclosingInfo enclosingInfo;
    private LazyClassSignature signature;
    private LexicalScope scope;

    private List<JFieldSymbol> fields = new ArrayList<>();
    private List<JClassSymbol> memberClasses = new ArrayList<>();
    private List<JMethodSymbol> methods = new ArrayList<>();
    private List<JConstructorSymbol> ctors = new ArrayList<>();
    private Set<String> enumConstantNames = null;

    private final ParseLock parseLock;

    /** Note that '.' is forbidden because in internal names they're replaced by slashes '/'. */
    private static final Pattern INTERNAL_NAME_FORBIDDEN_CHARS = Pattern.compile("[;<>\\[.]");

    private static boolean isValidInternalName(String internalName) {
        return !internalName.isEmpty() && !INTERNAL_NAME_FORBIDDEN_CHARS.matcher(internalName).find();
    }

    ClassStub(AsmSymbolResolver resolver, String internalName, @NonNull Loader loader, int observedArity) {
        assert isValidInternalName(internalName) : internalName;

        this.resolver = resolver;
        this.names = new Names(internalName);
        this.loader = loader;

        this.parseLock = new ParseLock() {
            // note to devs: to debug the parsing logic you might have
            // to replace the implementation of toString temporarily,
            // otherwise an IDE could call toString just to show the item
            // in the debugger view (which could cause parsing of the class file).

            @Override
            protected boolean doParse() throws IOException {
                try (InputStream instream = loader.getInputStream()) {
                    if (instream != null) {
                        ClassReader classReader = new ClassReader(instream);
                        ClassStubBuilder builder = new ClassStubBuilder(ClassStub.this, resolver);
                        classReader.accept(builder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                        return true;
                    } else {
                        return false;
                    }
                }
            }

            @Override
            protected void finishParse(boolean failed) {
                if (enclosingInfo == null) {
                    // this may be normal
                    enclosingInfo = EnclosingInfo.NO_ENCLOSING;
                }
                if (signature == null) {
                    assert failed : "No signature, but the parse hasn't failed? investigate";
                    signature = LazyClassSignature.defaultWhenUnresolved(ClassStub.this, observedArity);
                }
                methods = Collections.unmodifiableList(methods);
                ctors = Collections.unmodifiableList(ctors);
                fields = Collections.unmodifiableList(fields);
                memberClasses = Collections.unmodifiableList(memberClasses);
                enumConstantNames = enumConstantNames == null ? null : Collections.unmodifiableSet(enumConstantNames);

                if (enclosingInfo == EnclosingInfo.NO_ENCLOSING) {
                    if (names.canonicalName == null || names.simpleName == null) {
                        // This happens if the simple name contains dollars,
                        // in which case we might have an enclosing class, and
                        // we can only tell now (no enclosingInfo) that that's
                        // not the case.
                        names.finishOuterClass();
                    }
                }
            }

            @Override
            protected boolean postCondition() {
                return signature != null && enclosingInfo != null;
            }
        };
    }

    Loader getLoader() {
        return loader;
    }

    @Override
    public AsmSymbolResolver getResolver() {
        return resolver;
    }

    // <editor-fold  defaultstate="collapsed" desc="Setters used during loading">

    void setHeader(@Nullable String signature,
                   @Nullable String superName,
                   String[] interfaces) {
        this.signature = new LazyClassSignature(this, signature, superName, interfaces);
    }

    /**
     * Called if this is an inner class (their simple name cannot be
     * derived from splitting the internal/binary name on dollars, as
     * the simple name may itself contain dollars).
     */
    void setSimpleName(String simpleName) {
        this.names.simpleName = simpleName;
    }

    void setModifiers(int accessFlags, boolean fromClassInfo) {
        /*
            A different set of modifiers is contained in the ClassInfo
            structure and the InnerClasses structure. See
            https://docs.oracle.com/javase/specs/jvms/se13/html/jvms-4.html#jvms-4.1-200-E.1
            https://docs.oracle.com/javase/specs/jvms/se13/html/jvms-4.html#jvms-4.7.6-300-D.1-D.1

            Here is the diff (+ lines (resp. - lines) are only available
            in InnerClasses (resp. ClassInfo), the rest are available in both)

            ACC_PUBLIC      0x0001  Declared public; may be accessed from outside its package.
         +  ACC_PRIVATE     0x0002  Marked private in source.
         +  ACC_PROTECTED   0x0004  Marked protected in source.
         +  ACC_STATIC      0x0008  Marked or implicitly static in source.
            ACC_FINAL       0x0010  Declared final; no subclasses allowed.
         -  ACC_SUPER       0x0020  Treat superclass methods specially when invoked by the invokespecial instruction.
            ACC_INTERFACE   0x0200  Is an interface, not a class.
            ACC_ABSTRACT    0x0400  Declared abstract; must not be instantiated.
            ACC_SYNTHETIC   0x1000  Declared synthetic; not present in the source code.
            ACC_ANNOTATION  0x2000  Declared as an annotation type.
            ACC_ENUM        0x4000  Declared as an enum type.
         -  ACC_MODULE      0x8000  Is a module, not a class or interface.

            If this stub is a nested class, then we don't have all its
            modifiers just with the ClassInfo, the actual source-declared
            visibility (if not public) is only in the InnerClasses, as
            well as its ACC_STATIC.

            Also ACC_SUPER conflicts with ACC_SYNCHRONIZED, which
            Modifier.toString would reflect.

            Since the differences are disjoint we can just OR the two
            sets of flags.
         */

        int myAccess = this.accessFlags;
        if (fromClassInfo) {
            // we don't care about ACC_SUPER and it conflicts
            // with ACC_SYNCHRONIZED
            accessFlags = accessFlags & ~Opcodes.ACC_SUPER;
        } else if ((myAccess & Opcodes.ACC_PUBLIC) != 0
            && (accessFlags & Opcodes.ACC_PROTECTED) != 0) {
            // ClassInfo mentions ACC_PUBLIC even if the real
            // visibility is protected
            // We remove the public to avoid a "public protected" combination
            myAccess = myAccess & ~Opcodes.ACC_PUBLIC;
        }
        this.accessFlags = myAccess | accessFlags;

        if ((accessFlags & Opcodes.ACC_ENUM) != 0) {
            this.enumConstantNames = new HashSet<>();
        }
    }

    void setOuterClass(String outerName, @Nullable String methodName, @Nullable String methodDescriptor) {
        if (enclosingInfo == null) {
            if (outerName == null) {
                this.enclosingInfo = EnclosingInfo.NO_ENCLOSING;
            } else {
                this.enclosingInfo = new EnclosingInfo(resolver.resolveFromInternalNameCannotFail(outerName), methodName, methodDescriptor);
            }
        }
    }

    void addField(FieldStub fieldStub) {
        fields.add(fieldStub);

        if (fieldStub.isEnumConstant() && enumConstantNames != null) {
            enumConstantNames.add(fieldStub.getSimpleName());
        }
    }

    void addMemberClass(ClassStub classStub) {
        if (classStub.enclosingInfo == null) {
            classStub.enclosingInfo = new EnclosingInfo(this, null, null);
        }
        memberClasses.add(classStub);
    }

    void addMethod(MethodStub methodStub) {
        methods.add(methodStub);
    }

    void addCtor(CtorStub methodStub) {
        ctors.add(methodStub);
    }

    // </editor-fold>


    @Override
    public @Nullable JClassSymbol getSuperclass() {
        parseLock.ensureParsed();
        return signature.getRawSuper();
    }

    @Override
    public List<JClassSymbol> getSuperInterfaces() {
        parseLock.ensureParsed();
        return signature.getRawItfs();
    }

    @Override
    public @Nullable JClassType getSuperclassType(Substitution substitution) {
        parseLock.ensureParsed();
        return signature.getSuperType(substitution);
    }

    @Override
    public List<JClassType> getSuperInterfaceTypes(Substitution substitution) {
        parseLock.ensureParsed();
        return signature.getSuperItfs(substitution);
    }

    @Override
    public List<JTypeVar> getTypeParameters() {
        parseLock.ensureParsed();
        return signature.getTypeParams();
    }

    @Override
    public LexicalScope getLexicalScope() {
        if (scope == null) {
            scope = JClassSymbol.super.getLexicalScope();
        }
        return scope;
    }

    @Override
    public List<JFieldSymbol> getDeclaredFields() {
        parseLock.ensureParsed();
        return fields;
    }

    @Override
    public List<JMethodSymbol> getDeclaredMethods() {
        parseLock.ensureParsed();
        return methods;
    }

    @Override
    public List<JConstructorSymbol> getConstructors() {
        parseLock.ensureParsed();
        return ctors;
    }

    @Override
    public List<JClassSymbol> getDeclaredClasses() {
        parseLock.ensureParsed();
        return memberClasses;
    }

    @Override
    public @Nullable JClassSymbol getEnclosingClass() {
        parseLock.ensureParsed();
        return enclosingInfo.getEnclosingClass();
    }

    @Override
    public @Nullable JExecutableSymbol getEnclosingMethod() {
        parseLock.ensureParsed();
        return enclosingInfo.getEnclosingMethod();
    }

    @Override
    public @Nullable Set<String> getEnumConstantNames() {
        parseLock.ensureParsed();
        return enumConstantNames;
    }

    @Override
    public JTypeParameterOwnerSymbol getEnclosingTypeParameterOwner() {
        parseLock.ensureParsed();
        return enclosingInfo.getEnclosing();
    }

    @Override
    public String toString() {
        return SymbolToStrings.ASM.toString(this);
    }

    @Override
    public int hashCode() {
        return SymbolEquality.CLASS.hash(this);
    }

    @Override
    public boolean equals(Object obj) {
        return SymbolEquality.CLASS.equals(this, obj);
    }

    // <editor-fold  defaultstate="collapsed" desc="Names">

    public String getInternalName() {
        return getNames().internalName;
    }

    private Names getNames() {
        return names;
    }

    @Override
    public @NonNull String getBinaryName() {
        return getNames().binaryName;
    }

    /**
     * Simpler check than computing the canonical name.
     */
    boolean hasCanonicalName() {
        if (names.canonicalName != null) {
            return true;
        }
        parseLock.ensureParsed();
        if (isAnonymousClass() || isLocalClass()) {
            return false;
        }
        JClassSymbol enclosing = getEnclosingClass();
        return enclosing == null // top-level class
            || enclosing instanceof ClassStub
            && ((ClassStub) enclosing).hasCanonicalName();
    }

    @Override
    public String getCanonicalName() {
        String canoName = names.canonicalName;
        if (canoName == null) {
            parseLock.ensureParsed();
            canoName = names.canonicalName;
            if (canoName != null) {
                return canoName;
            }
            JClassSymbol enclosing = getEnclosingClass();
            if (enclosing == null) {
                canoName = names.packageName + '.' + getSimpleName();
                names.canonicalName = canoName;
                return canoName;
            }
            String outerName = enclosing.getCanonicalName();
            if (outerName == null) {
                return null;
            }
            canoName = outerName + '.' + getSimpleName();
            names.canonicalName = canoName;
            return canoName;
        }
        return canoName;
    }

    @Override
    public @NonNull String getPackageName() {
        return getNames().packageName;
    }

    @Override
    public @NonNull String getSimpleName() {
        String mySimpleName = names.simpleName;
        if (mySimpleName == null) {
            parseLock.ensureParsed();
            return Objects.requireNonNull(names.simpleName);
        }
        return mySimpleName;
    }

    @Override
    public TypeSystem getTypeSystem() {
        return getResolver().getTypeSystem();
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="Modifier info">


    @Override
    public boolean isUnresolved() {
        return parseLock.isFailed();
    }

    @Override
    public boolean isArray() {
        return false;
    }

    @Override
    public boolean isPrimitive() {
        return false;
    }

    @Override
    public @Nullable JTypeDeclSymbol getArrayComponent() {
        return null;
    }

    @Override
    public int getModifiers() {
        parseLock.ensureParsed();
        return accessFlags;
    }

    @Override
    public boolean isAbstract() {
        return (getModifiers() & Opcodes.ACC_ABSTRACT) != 0;
    }

    @Override
    public boolean isEnum() {
        return (getModifiers() & Opcodes.ACC_ENUM) != 0;
    }

    @Override
    public boolean isAnnotation() {
        return (getModifiers() & Opcodes.ACC_ANNOTATION) != 0;
    }

    @Override
    public boolean isInterface() {
        return (getModifiers() & Opcodes.ACC_INTERFACE) != 0;
    }

    @Override
    public boolean isClass() {
        return (getModifiers() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) == 0;
    }

    @Override
    public boolean isRecord() {
        JClassSymbol sup = getSuperclass();
        return sup != null && "java.lang.Record".equals(sup.getBinaryName());
    }

    @Override
    public boolean isLocalClass() {
        return enclosingInfo.isLocal();
    }

    @Override
    public boolean isAnonymousClass() {
        return getSimpleName().isEmpty();
    }

    // </editor-fold>


    static class Names {

        final String binaryName;
        final String internalName;
        final String packageName;
        /** If null, the class requires parsing to find out the actual canonical name. */
        @Nullable String canonicalName;
        /** If null, the class requires parsing to find out the actual simple name. */
        @Nullable String simpleName;

        Names(String internalName) {
            assert isValidInternalName(internalName) : internalName;
            int packageEnd = internalName.lastIndexOf('/');

            this.internalName = internalName;
            this.binaryName = internalName.replace('/', '.');
            if (packageEnd == -1) {
                this.packageName = "";
            } else {
                this.packageName = binaryName.substring(0, packageEnd);
            }

            if (binaryName.indexOf('$', packageEnd + 1) >= 0) {
                // Contains a dollar in class name (after package)
                // Requires parsing to find out the actual simple name,
                // this might be an inner class, or simply a class with
                // a dollar in its name.

                // ASSUMPTION: all JVM languages use the $ convention
                // to separate inner classes. Java compilers do so but
                // not necessarily true of all compilers/languages.
                this.canonicalName = null;
                this.simpleName = null;
            } else {
                // fast path
                this.canonicalName = binaryName;
                this.simpleName = binaryName.substring(packageEnd + 1);
            }
        }

        public void finishOuterClass() {
            int packageEnd = internalName.lastIndexOf('/');
            this.simpleName = binaryName.substring(packageEnd + 1); // if -1, start from 0
            this.canonicalName = binaryName;
        }
    }

    static class EnclosingInfo {

        static final EnclosingInfo NO_ENCLOSING = new EnclosingInfo(null, null, null);

        private final @Nullable JClassSymbol stub;
        private final @Nullable String methodName;
        private final @Nullable String methodDescriptor;

        EnclosingInfo(@Nullable JClassSymbol stub, @Nullable String methodName, @Nullable String methodDescriptor) {
            this.stub = stub;
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
        }

        boolean isLocal() {
            return methodName != null || methodDescriptor != null;
        }

        public @Nullable JClassSymbol getEnclosingClass() {
            return stub;
        }

        public @Nullable MethodStub getEnclosingMethod() {
            if (stub instanceof ClassStub && methodName != null) {
                ClassStub stub1 = (ClassStub) stub;
                stub1.parseLock.ensureParsed();
                for (JMethodSymbol m : stub1.methods) {
                    MethodStub ms = (MethodStub) m;
                    if (ms.matches(methodName, methodDescriptor)) {
                        return ms;
                    }
                }
            }
            return null;
        }


        JTypeParameterOwnerSymbol getEnclosing() {
            if (methodName != null) {
                return getEnclosingMethod();
            } else {
                return getEnclosingClass();
            }
        }
    }
}
