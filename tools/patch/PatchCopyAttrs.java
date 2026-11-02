import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 把 com.android.utils.FileUtils 里 Files.copy(..., COPY_ATTRIBUTES) 的
 * COPY_ATTRIBUTES 换成 REPLACE_EXISTING（重复传一次无副作用）。
 *
 * 为什么要这样：本机 seccomp 对 futimes / utimensat 一律返回 EPERM，
 * 而 Files.copy 的“连属性一起拷”是 JDK 内建行为，LD_PRELOAD 拦不住，
 * 于是 AGP 合并资源（MergeResources）在拷 values.xml 时必然失败。
 * 只去掉“拷时间戳”这一步，字节内容照旧完整拷贝。
 */
public class PatchCopyAttrs {

    public static void main(String[] args) throws Exception {
        for (String arg : args) patchJar(Paths.get(arg));
    }

    private static void patchJar(Path jar) throws IOException {
        boolean changed = false;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar));
             ZipOutputStream out = new ZipOutputStream(buffer)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (entry.getName().equals("com/android/utils/FileUtils.class")) {
                    byte[] next = patchClass(data);
                    if (next != null) { data = next; changed = true; }
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0L);
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        if (!changed) return;
        Path tmp = jar.resolveSibling(jar.getFileName() + ".patched");
        Files.write(tmp, buffer.toByteArray());
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("已修补 " + jar);
    }

    /** 返回 null 表示这个 class 里没有需要改的地方 */
    private static byte[] patchClass(byte[] data) {
        ClassReader reader = new ClassReader(data);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] touched = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor base = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, base) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String fieldDesc) {
                        if (opcode == Opcodes.GETSTATIC
                                && owner.equals("java/nio/file/StandardCopyOption")
                                && field.equals("COPY_ATTRIBUTES")) {
                            touched[0] = true;
                            super.visitFieldInsn(opcode, owner, "REPLACE_EXISTING", fieldDesc);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, field, fieldDesc);
                    }
                };
            }
        }, 0);
        return touched[0] ? writer.toByteArray() : null;
    }
}
