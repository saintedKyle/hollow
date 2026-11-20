package hollow.injector;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal class-file reader. Reads exactly what the injector needs and nothing
 * else: the constant pool, the access flags, and the method table.
 *
 * No ASM, no javassist, no runtime dependency. The JDK that runs the injector
 * does not have to be the JDK that compiled the payload.
 */
final class ClassFile {

    static final String AGENT_MAIN_LONG = "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V";
    static final String AGENT_MAIN_SHORT = "(Ljava/lang/String;)V";

    static final class Method {
        final String name;
        final String descriptor;
        final int access;

        Method(String name, String descriptor, int access) {
            this.name = name;
            this.descriptor = descriptor;
            this.access = access;
        }

        boolean isPublicStatic() {
            return (access & 0x0001) != 0 && (access & 0x0008) != 0;
        }

        boolean isEntryNamed(String candidate) {
            return name.equals(candidate) && isPublicStatic()
                    && (descriptor.equals(AGENT_MAIN_LONG) || descriptor.equals(AGENT_MAIN_SHORT));
        }
    }

    final String internalName;
    final int majorVersion;
    final List<Method> methods;

    private ClassFile(String internalName, int majorVersion, List<Method> methods) {
        this.internalName = internalName;
        this.majorVersion = majorVersion;
        this.methods = methods;
    }

    String binaryName() {
        return internalName.replace('/', '.');
    }

    String packageName() {
        int cut = internalName.lastIndexOf('/');
        return cut < 0 ? "" : internalName.substring(0, cut).replace('/', '.');
    }

    boolean hasAgentMain() {
        for (Method m : methods) {
            if (m.isEntryNamed("agentmain")) return true;
        }
        return false;
    }

    boolean hasPremain() {
        for (Method m : methods) {
            if (m.isEntryNamed("premain")) return true;
        }
        return false;
    }

    static ClassFile parse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 8192));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();                       // minor
        int major = in.readUnsignedShort();

        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        int[] classNameAt = new int[cpCount];

        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.skipNBytes(4);                       // Integer, Float
                case 5, 6 -> { in.skipNBytes(8); i++; }              // Long, Double take two slots
                case 7 -> classNameAt[i] = in.readUnsignedShort();   // Class
                case 8, 16, 19, 20 -> in.skipNBytes(2);              // String, MethodType, Module, Package
                case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);      // refs, NameAndType, Dynamic, InvokeDynamic
                case 15 -> in.skipNBytes(3);                         // MethodHandle
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }

        in.readUnsignedShort();                       // access flags
        int thisClass = in.readUnsignedShort();
        in.readUnsignedShort();                       // super class
        int interfaces = in.readUnsignedShort();
        in.skipNBytes(2L * interfaces);

        skipMembers(in);                              // fields

        int methodCount = in.readUnsignedShort();
        List<Method> methods = new ArrayList<>(methodCount);
        for (int i = 0; i < methodCount; i++) {
            int access = in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            skipAttributes(in);
            methods.add(new Method(name, descriptor, access));
        }

        int nameIndex = classNameAt[thisClass];
        if (nameIndex <= 0 || nameIndex >= cpCount || utf8[nameIndex] == null) {
            throw new IOException("bad this_class");
        }
        return new ClassFile(utf8[nameIndex], major, methods);
    }

    static ClassFile parse(byte[] bytes) throws IOException {
        return parse(new ByteArrayInputStream(bytes));
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipNBytes(6);                         // access, name, descriptor
            skipAttributes(in);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();                   // attribute name
            long length = Integer.toUnsignedLong(in.readInt());
            in.skipNBytes(length);
        }
    }
}
