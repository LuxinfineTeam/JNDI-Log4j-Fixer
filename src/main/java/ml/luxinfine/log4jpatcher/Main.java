package ml.luxinfine.log4jpatcher;

import org.ow2.util.asm.ClassReader;
import org.ow2.util.asm.ClassWriter;
import org.ow2.util.asm.Opcodes;

import java.nio.file.*;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        System.out.print("Enter the path to Logj4 jar: ");
        Scanner scanner = new Scanner(System.in);
        String path = scanner.nextLine();
        if(!path.endsWith(".jar")) {
            System.out.println("The file path must end in .jar!");
            return;
        }

        Path zipFilePath = Paths.get(path);
        if(!Files.exists(zipFilePath)) {
            System.out.println("The file in the entered path does not exist!");
            return;
        }
        byte[] bytecode = null;
        try (FileSystem fs = FileSystems.newFileSystem(zipFilePath, null)) {
            Path source = fs.getPath("/org/apache/logging/log4j/core/lookup/Interpolator.class");
            if(!Files.exists(source)) {
                System.out.println("Cant found 'org/apache/logging/log4j/core/lookup/Interpolator.class' in jar entry!");
                return;
            }
            bytecode = Files.readAllBytes(source);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        if (bytecode == null) {
            System.out.println("Cant read 'org.apache.logging.log4j.core.lookup.Interpolator' class!");
            return;
        }

        org.ow2.util.asm.tree.ClassNode classNode = new org.ow2.util.asm.tree.ClassNode();
        int majorVersion = ((bytecode[6] & 0xFF) << 8) | (bytecode[7] & 0xFF);
        boolean java7 = majorVersion > 50;
        ClassReader classReader = new ClassReader(bytecode);
        classReader.accept(classNode, java7 ? ClassReader.SKIP_FRAMES : ClassReader.EXPAND_FRAMES);
        Optional<org.ow2.util.asm.tree.MethodNode> m = ((List<org.ow2.util.asm.tree.MethodNode>) classNode.methods).stream().filter(method -> method.name.equals("<init>") && method.desc.equals("()V")).findFirst();
        if(!m.isPresent()) {
            System.out.println("Couldn't find a class constructor for the patch!");
            return;
        }
        org.ow2.util.asm.tree.MethodNode method = m.get();
        org.ow2.util.asm.tree.AbstractInsnNode endNode = null;
        for(int id = 0; id < method.instructions.size(); id++) {
            org.ow2.util.asm.tree.AbstractInsnNode inst = method.instructions.get(id);
            if(inst.getOpcode() == Opcodes.ALOAD && ((org.ow2.util.asm.tree.VarInsnNode)inst).var == 0) {
                org.ow2.util.asm.tree.AbstractInsnNode getField = inst.getNext();
                if(getField == null || getField.getOpcode() != Opcodes.GETFIELD) continue;
                org.ow2.util.asm.tree.FieldInsnNode insn = ((org.ow2.util.asm.tree.FieldInsnNode)getField);
                if(!insn.owner.equals("org/apache/logging/log4j/core/lookup/Interpolator") || !insn.name.equals("lookups") || !insn.desc.equals("Ljava/util/Map;")) continue;
                org.ow2.util.asm.tree.AbstractInsnNode ldc = getField.getNext();
                if(ldc == null || ldc.getOpcode() != Opcodes.LDC || !"jndi".equals(((org.ow2.util.asm.tree.LdcInsnNode)ldc).cst)) continue;
                org.ow2.util.asm.tree.AbstractInsnNode newInst = ldc.getNext();
                if(newInst == null || newInst.getOpcode() != Opcodes.NEW || !((org.ow2.util.asm.tree.TypeInsnNode)newInst).desc.equals("org/apache/logging/log4j/core/lookup/JndiLookup")) continue;
                org.ow2.util.asm.tree.AbstractInsnNode dup = newInst.getNext();
                if(dup == null || dup.getOpcode() != Opcodes.DUP) continue;
                org.ow2.util.asm.tree.AbstractInsnNode special = dup.getNext();
                if(special == null || special.getOpcode() != Opcodes.INVOKESPECIAL) continue;
                org.ow2.util.asm.tree.MethodInsnNode specialNode = (org.ow2.util.asm.tree.MethodInsnNode) special;
                if(!specialNode.owner.equals("org/apache/logging/log4j/core/lookup/JndiLookup") || !specialNode.name.equals("<init>") || !specialNode.desc.equals("()V")) continue;
                org.ow2.util.asm.tree.AbstractInsnNode invoke = special.getNext();
                if(invoke == null || invoke.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                org.ow2.util.asm.tree.MethodInsnNode invokeNode = (org.ow2.util.asm.tree.MethodInsnNode) invoke;
                if(!invokeNode.owner.equals("java/util/Map") || !invokeNode.name.equals("put") || !invokeNode.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")) continue;
                org.ow2.util.asm.tree.AbstractInsnNode pop = invoke.getNext();
                if(pop == null || pop.getOpcode() != Opcodes.POP) continue;
                endNode = pop;
                break;
            }
        }
        if (endNode == null) {
            System.out.println("Couldn't find a 'lookups.put(\"jndi\", new JndiLookup());' instructions in the class constructor!");
            return;
        }
        int count = 8;
        while (count > 0 && endNode.getPrevious() != null) {
            org.ow2.util.asm.tree.AbstractInsnNode tmp = endNode.getPrevious();
            method.instructions.remove(endNode);
            endNode = tmp;
            count--;
        }

        ClassWriter writer = new ClassWriter(java7 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        bytecode = writer.toByteArray();

        Path result = Paths.get(path.substring(0, path.length() - 4) + "-fix.jar");
        try {
            Files.copy(zipFilePath, result);
            try (FileSystem fs = FileSystems.newFileSystem(result, null)) {
                Path target = fs.getPath("/org/apache/logging/log4j/core/lookup/Interpolator.class");
                Files.write(target, bytecode);
                System.out.println("The patch was successful!");
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
