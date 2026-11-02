import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 sun.nio.fs.UnixCopyFile$Flags 里 “拷属性失败就当错误” 的三个开关关掉。
 *
 * 背景：本机 seccomp 对 futimes / utimensat 一律返回 EPERM，而
 * Files.copy(..., COPY_ATTRIBUTES) 是 JDK 内建行为，LD_PRELOAD 拦不住，
 * 于是任何带 COPY_ATTRIBUTES 的拷贝（AGP 合并资源、KSP 增量缓存……）
 * 都会直接抛 FileSystemException。
 *
 * 只改 failIfUnableToCopy*，文件内容、权限、所有者照旧拷贝，
 * 唯一被忽略的是 “时间戳没拷过去” 这一件事——对构建产物没有影响。
 */
public class PatchJdkCopyFlags {

    private static final String CLASS_FILE = "sun/nio/fs/UnixCopyFile$Flags.class";

    public static void main(String[] args) throws IOException {
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        byte[] data = Files.readAllBytes(in);
        byte[] patched = patch(data);
        Files.createDirectories(out.getParent());
        Files.write(out, patched);
        System.out.println("已修补 " + CLASS_FILE + " → " + out);
    }

    private static byte[] patch(byte[] data) {
        ClassReader reader = new ClassReader(data);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor base = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, base) {
                    /** 暂存紧挨着 putfield 的那条 ICONST_1，等到看清字段名再决定怎么写 */
                    private boolean sawIconst1 = false;

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ICONST_1) {
                            sawIconst1 = true;
                            return;
                        }
                        flush();
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String fieldDesc) {
                        if (opcode == Opcodes.PUTFIELD
                                && field.startsWith("failIfUnableToCopy")
                                && sawIconst1) {
                            sawIconst1 = false;
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitFieldInsn(opcode, owner, field, fieldDesc);
                            return;
                        }
                        flush();
                        super.visitFieldInsn(opcode, owner, field, fieldDesc);
                    }

                    private void flush() {
                        if (sawIconst1) {
                            sawIconst1 = false;
                            super.visitInsn(Opcodes.ICONST_1);
                        }
                    }

                    @Override
                    public void visitEnd() {
                        flush();
                        super.visitEnd();
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
