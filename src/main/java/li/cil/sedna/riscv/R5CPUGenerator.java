package li.cil.sedna.riscv;

import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.instruction.*;
import li.cil.sedna.instruction.decoder.*;
import li.cil.sedna.riscv.exception.R5Exception;
import li.cil.sedna.riscv.exception.R5IllegalInstructionException;
import li.cil.sedna.utils.BitUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.core.util.Throwables;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public final class R5CPUGenerator {
    @SuppressWarnings("unchecked")
    private static final Class<R5CPU> GENERATED_CLASS = (Class<R5CPU>) generateClass();
    private static final Constructor<R5CPU> GENERATED_CLASS_CTOR;

    public static Class<R5CPU> getGeneratedClass() {
        return GENERATED_CLASS;
    }

    public static R5CPU create(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {
        try {
            return GENERATED_CLASS_CTOR.newInstance(physicalMemory, rtc);
        } catch (final InvocationTargetException e) {
            Throwables.rethrow(e.getCause());
            throw new AssertionError();
        } catch (final InstantiationException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    static {
        try {
            GENERATED_CLASS_CTOR = GENERATED_CLASS.getConstructor(MemoryMap.class, RealTimeCounter.class);
            GENERATED_CLASS_CTOR.setAccessible(true);
        } catch (final NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Class<?> generateClass() {
        try {
            final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            final ClassRemapper remapper = new ClassRemapper(writer, new Remapper() {
                private final String TEMPLATE_NAME = Type.getInternalName(R5CPUTemplate.class);

                @Override
                public String map(final String internalName) {
                    if (internalName.equals(TEMPLATE_NAME)) {
                        return TEMPLATE_NAME + "$Generated";
                    } else {
                        return super.map(internalName);
                    }
                }
            });

            final ClassReader reader = new ClassReader(R5CPUTemplate.class.getName());
            reader.accept(new TemplateClassVisitor(remapper), ClassReader.EXPAND_FRAMES);

            final byte[] bytes = writer.toByteArray();

            final Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            return (Class<?>) defineClass.invoke(R5CPUTemplate.class.getClassLoader(), null, bytes, 0, bytes.length);
        } catch (final Throwable e) {
            throw new AssertionError(e);
        }
    }

    private static final class TemplateClassVisitor extends ClassVisitor implements Opcodes {
        public TemplateClassVisitor(final ClassVisitor cv) {
            super(ASM7, cv);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
            if ("interpretTrace".equals(name)) {
                return new TemplateMethodVisitor(super.cv, super.visitMethod(access, name, descriptor, signature, exceptions));
            } else {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }

        @Override
        public void visitInnerClass(final String name, final String outerName, final String innerName, final int access) {
        }
    }

    private static final class TemplateMethodVisitor extends MethodVisitor implements Opcodes {
        private final ClassVisitor classVisitor;

        public TemplateMethodVisitor(final ClassVisitor classVisitor, final MethodVisitor methodVisitor) {
            super(Opcodes.ASM7, methodVisitor);
            this.classVisitor = classVisitor;
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
            if (!"decode".equals(name)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }

            R5Instructions.DECODER.accept(new DecoderGenerator(classVisitor, super.mv));
        }
    }

    private static final class GeneratorContext implements Opcodes {
        private static final int LOCAL_THIS = 0;
        private static final int LOCAL_INST = 2;
        private static final int LOCAL_INST_OFFSET = 3;
        private static final int LOCAL_TO_PC = 4;
        private static final int LOCAL_FIRST_FIELD = 6;

        public final ClassVisitor classVisitor;
        public final MethodVisitor methodVisitor;
        public final boolean isTopLevel;
        public final int processedMask;
        public final int localInst;
        public final int localFirstField;

        public final Label continueLabel;
        public final Label illegalInstructionLabel;
        private final Object2IntArrayMap<FieldInstructionArgument> localVariables;

        private GeneratorContext(final ClassVisitor classVisitor, final MethodVisitor methodVisitor) {
            this(classVisitor, methodVisitor, true, 0, LOCAL_INST, LOCAL_FIRST_FIELD, new Label(), new Label(), new Object2IntArrayMap<>());
        }

        private GeneratorContext(final ClassVisitor classVisitor,
                                 final MethodVisitor methodVisitor,
                                 final boolean isTopLevel,
                                 final int processedMask,
                                 final int localInst,
                                 final int localFirstField,
                                 final Label continueLabel,
                                 final Label illegalInstructionLabel,
                                 final Object2IntArrayMap<FieldInstructionArgument> localVariables) {
            this.classVisitor = classVisitor;
            this.methodVisitor = methodVisitor;
            this.isTopLevel = isTopLevel;
            this.processedMask = processedMask;
            this.localInst = localInst;
            this.localFirstField = localFirstField;
            this.continueLabel = continueLabel;
            this.illegalInstructionLabel = illegalInstructionLabel;
            this.localVariables = localVariables;
        }

        public GeneratorContext withProcessed(final int mask) {
            return new GeneratorContext(classVisitor, methodVisitor, isTopLevel, processedMask | mask,
                    localInst, localFirstField, continueLabel, illegalInstructionLabel, localVariables);
        }

        public void generateContinue() {
            if (isTopLevel) {
                methodVisitor.visitJumpInsn(GOTO, continueLabel);
            } else {
                methodVisitor.visitInsn(RETURN);
            }
        }

        public void generateIllegalInstruction() {
            methodVisitor.visitJumpInsn(GOTO, illegalInstructionLabel);
        }

        public void generateInstOffsetInc(final int value) {
            if (isTopLevel) {
                generateInstOffsetIncUnsafe(value);
            }
        }

        public void generateInstOffsetIncUnsafe(final int value) {
            assert isTopLevel;
            methodVisitor.visitIincInsn(LOCAL_INST_OFFSET, value);
        }

        public void generateSavePC() {
            assert isTopLevel;
            methodVisitor.visitVarInsn(ALOAD, LOCAL_THIS);
            methodVisitor.visitVarInsn(ILOAD, LOCAL_INST_OFFSET);
            methodVisitor.visitVarInsn(ILOAD, LOCAL_TO_PC);
            methodVisitor.visitInsn(IADD);
            methodVisitor.visitFieldInsn(PUTFIELD, Type.getInternalName(R5CPUTemplate.class), "pc", "I");
        }

        public void generateGetField(final FieldInstructionArgument argument) {
            methodVisitor.visitInsn(ICONST_0);
            for (final InstructionFieldMapping mapping : argument.mappings) {
                methodVisitor.visitVarInsn(ILOAD, localInst);
                methodVisitor.visitLdcInsn(mapping.srcLSB);
                methodVisitor.visitLdcInsn(mapping.srcMSB);
                methodVisitor.visitLdcInsn(mapping.dstLSB);
                methodVisitor.visitMethodInsn(INVOKESTATIC, Type.getInternalName(BitUtils.class),
                        "getField", "(IIII)I", false);
                if (mapping.signExtend) {
                    methodVisitor.visitLdcInsn(mapping.dstLSB + (mapping.srcMSB - mapping.srcLSB) + 1);
                    methodVisitor.visitMethodInsn(INVOKESTATIC, Type.getInternalName(BitUtils.class),
                            "extendSign", "(II)I", false);
                }
                methodVisitor.visitInsn(IOR);
            }

            switch (argument.postprocessor) {
                case NONE:
                    break;
                case ADD_8:
                    methodVisitor.visitLdcInsn(8);
                    methodVisitor.visitInsn(IADD);
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        }
    }

    private static final class DecoderGenerator implements DecoderTreeVisitor, Opcodes {
        private final GeneratorContext context;

        public DecoderGenerator(final ClassVisitor classVisitor, final MethodVisitor methodVisitor) {
            context = new GeneratorContext(classVisitor, methodVisitor);
        }

        @Override
        public DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node) {
            return new SwitchVisitor(context);
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node) {
            return new BranchVisitor(context);
        }

        @Override
        public DecoderTreeLeafVisitor visitInstruction() {
            return new LeafVisitor(context);
        }

        @Override
        public void visitEnd() {
            context.methodVisitor.visitLabel(context.illegalInstructionLabel);
            context.methodVisitor.visitTypeInsn(NEW, Type.getInternalName(R5IllegalInstructionException.class));
            context.methodVisitor.visitInsn(DUP);
            context.methodVisitor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(R5IllegalInstructionException.class), "<init>", "()V", false);
            context.methodVisitor.visitInsn(ATHROW);

            if (context.isTopLevel) {
                context.methodVisitor.visitLabel(context.continueLabel);
            }
        }
    }

    private static final class InnerNodeVisitor implements DecoderTreeVisitor, Opcodes {
        private final GeneratorContext context;
        private final Label endLabel;

        private GeneratorContext childContext;

        public InnerNodeVisitor(final GeneratorContext context, final Label endLabel) {
            this.context = context;
            this.endLabel = endLabel;
        }

        @Override
        public DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node) {
            return new SwitchVisitor(generateMethodInvocation(node));
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node) {
            return new BranchVisitor(generateMethodInvocation(node));
        }

        @Override
        public DecoderTreeLeafVisitor visitInstruction() {
            return new LeafVisitor(context);
        }

        @Override
        public void visitEnd() {
            if (childContext != null) {
                childContext.methodVisitor.visitLabel(childContext.illegalInstructionLabel);
                childContext.methodVisitor.visitTypeInsn(NEW, Type.getInternalName(R5IllegalInstructionException.class));
                childContext.methodVisitor.visitInsn(DUP);
                childContext.methodVisitor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(R5IllegalInstructionException.class), "<init>", "()V", false);
                childContext.methodVisitor.visitInsn(ATHROW);

                childContext.methodVisitor.visitMaxs(-1, -1);
                childContext.methodVisitor.visitEnd();
            }

            if (endLabel != null) {
                context.methodVisitor.visitLabel(endLabel);
            }
        }

        private GeneratorContext generateMethodInvocation(final AbstractDecoderTreeNode node) {
            final OptionalInt commonInstructionSize = computeCommonInstructionSize(node);
            final boolean containsReturns = computeContainsReturns(node);
            final boolean canGenerateMethod = commonInstructionSize.isPresent() && !containsReturns;
            if (!canGenerateMethod) {
                return context;
            }

            final ArrayList<FieldInstructionArgument> parameters = new ArrayList<>(node.getArguments().arguments.keySet());
            parameters.retainAll(context.localVariables.keySet());

            final Object2IntArrayMap<FieldInstructionArgument> localsInMethod = new Object2IntArrayMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                localsInMethod.put(parameters.get(i), i + 1 /* this */ + 1 /* inst */);
            }

            final String methodName = node.getInstructions().map(i -> i.displayName.replaceAll("[^a-z^A-Z]", "_")).collect(Collectors.joining("$")) + "." + System.nanoTime();
            final String methodDescriptor = "(" + StringUtils.repeat('I', 1 + parameters.size()) + ")V";

            context.methodVisitor.visitVarInsn(ALOAD, GeneratorContext.LOCAL_THIS);
            context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
            for (final FieldInstructionArgument parameter : parameters) {
                final int localIndex = context.localVariables.getInt(parameter);
                context.methodVisitor.visitVarInsn(ILOAD, localIndex);
            }
            context.methodVisitor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(R5CPUTemplate.class),
                    methodName, methodDescriptor, false);

            context.generateInstOffsetInc(commonInstructionSize.getAsInt());
            context.generateContinue();

            final MethodVisitor childVisitor = context.classVisitor.visitMethod(ACC_PRIVATE,
                    methodName, methodDescriptor, null, new String[]{
                            // TODO Build this from the actual set of exceptions in the wrapped methods not just the worst-case?
                            Type.getInternalName(R5Exception.class),
                            Type.getInternalName(MemoryAccessException.class)
                    });
            childVisitor.visitCode();

            childContext = new GeneratorContext(context.classVisitor,
                    childVisitor,
                    false,
                    context.processedMask,
                    1,
                    1 /* this */ + 1 /* inst */ + parameters.size(),
                    null,
                    new Label(),
                    localsInMethod);

            return childContext;
        }

        private OptionalInt computeCommonInstructionSize(final AbstractDecoderTreeNode node) {
            final List<Integer> sizes = node.getInstructions().map(i -> i.size).distinct().collect(Collectors.toList());
            return sizes.size() == 1 ? OptionalInt.of(sizes.get(0)) : OptionalInt.empty();
        }

        private boolean computeContainsReturns(final AbstractDecoderTreeNode node) {
            return node.getInstructions()
                    .map(R5Instructions::getDefinition)
                    .filter(Objects::nonNull)
                    .anyMatch(d -> d.writesPC || d.returnsBoolean);
        }
    }

    private static final class SwitchVisitor extends LocalVariableOwner implements DecoderTreeSwitchVisitor {
        private final Label defaultCase = new Label();
        private Label[] cases;
        private int switchMask;

        public SwitchVisitor(final GeneratorContext context) {
            super(context);
        }

        @Override
        public void visit(final int mask, final int[] patterns, final DecoderTreeNodeFieldInstructionArguments arguments) {
            pushLocalVariables(arguments);

            final int caseCount = patterns.length;
            cases = new Label[caseCount];
            for (int i = 0; i < caseCount; i++) {
                cases[i] = new Label();
            }

            switchMask = mask & ~context.processedMask;
            final int unprocessedMask = mask & ~context.processedMask;
            final ArrayList<MaskField> maskFields = MaskField.create(unprocessedMask);

            // If we have mask fields that are the same in all patterns, generate an early-out if.
            // Not so much because of the early out, but because it allows us to simplify the switch
            // and increases the likelihood that we'll get sequential patterns.
            final ArrayList<MaskField> maskFieldsWithEqualPatterns = new ArrayList<>();
            for (int i = maskFields.size() - 1; i >= 0; i--) {
                final int fieldMask = maskFields.get(i).asMask();
                final int pattern = patterns[0] & fieldMask;

                boolean patternsMatch = true;
                for (int j = 1; j < caseCount; j++) {
                    if ((patterns[j] & fieldMask) != pattern) {
                        patternsMatch = false;
                        break;
                    }
                }

                if (patternsMatch) {
                    maskFieldsWithEqualPatterns.add(maskFields.remove(i));
                }
            }

            if (maskFields.isEmpty()) {
                throw new IllegalStateException(String.format("All cases in a switch node have the same patterns: [%s]",
                        maskFieldsWithEqualPatterns.stream().map(f -> Integer.toBinaryString(patterns[0] & f.asMask())).collect(Collectors.joining(", "))));
            }

            if (!maskFieldsWithEqualPatterns.isEmpty()) {
                int commonMask = 0;
                for (final MaskField maskField : maskFieldsWithEqualPatterns) {
                    commonMask |= maskField.asMask();
                }
                final int commonPattern = patterns[0] & commonMask;

                final Label switchLabel = new Label();

                context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
                context.methodVisitor.visitLdcInsn(commonMask);
                context.methodVisitor.visitInsn(IAND);
                context.methodVisitor.visitLdcInsn(commonPattern);
                context.methodVisitor.visitJumpInsn(IF_ICMPEQ, switchLabel);
                context.generateIllegalInstruction();
                context.methodVisitor.visitLabel(switchLabel);
            }

            // Try compressing mask by making mask adjacent and see if patterns also
            // compressed this way lead to a sequence, enabling a table switch.
            // TODO Test different field permutations?
            final int[] tablePatterns = new int[caseCount];
            for (int i = 0; i < caseCount; i++) {
                int tablePattern = 0;
                int offset = 0;
                for (final MaskField maskField : maskFields) {
                    tablePattern |= ((patterns[i] & maskField.asMask()) >>> maskField.srcLSB) << offset;
                    offset += maskField.srcMSB - maskField.srcLSB + 1;
                }
                tablePatterns[i] = tablePattern;
            }

            final Label[] tableLabels = sortPatternsAndGetRemappedLabels(tablePatterns);

            // Decide whether to do a tableswitch or lookupswitch. This uses the same heuristic
            // as javac: estimate space and time cost are accounted for, where time cost is weighted
            // more strongly (that's the *3).
            final int tableMax = tablePatterns[tablePatterns.length - 1];
            final int tableMin = tablePatterns[0];
            final int tableSize = tableMax - tableMin + 1;
            final int tableSpaceCost = 4 + tableSize;
            final int tableTimeCost = 3; // Comparisons if in range.
            final int tableInstructionMaskingCost; // Take into account instruction field extraction operations.
            if (maskFields.size() == 1 && maskFields.get(0).srcLSB == 0) {
                tableInstructionMaskingCost = 0;
            } else {
                tableInstructionMaskingCost = 1 + ((maskFields.get(0).srcLSB == 0) ? 4 : 6) + (maskFields.size() - 1) * (3 + 2 + 2 + 1) - 3;
            }
            final int lookupSpaceCost = 3 + 2 * tablePatterns.length;
            final int lookupTimeCost = tablePatterns.length;
            if (tableInstructionMaskingCost + tableSpaceCost + 3 * tableTimeCost <= lookupSpaceCost + 3 * lookupTimeCost) { // TableSwitch
                // Fill potential gaps.
                final Label[] labels = new Label[tableSize];
                int currentPattern = 0;
                for (int i = 0; i < tableSize; i++) {
                    if (tablePatterns[currentPattern] == tableMin + i) {
                        labels[i] = tableLabels[currentPattern];
                        currentPattern++;
                    } else {
                        labels[i] = defaultCase;
                    }
                }

                if (maskFields.size() == 1 && maskFields.get(0).srcLSB == 0) {
                    // Trivial case: no shifting needed to mask instruction.
                    for (int i = 0; i < caseCount; i++) {
                        assert (patterns[i] & unprocessedMask) == (tablePatterns[i] & unprocessedMask);
                    }
                    context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
                    context.methodVisitor.visitLdcInsn(unprocessedMask);
                    context.methodVisitor.visitInsn(IAND);
                } else {
                    // General case: mask out fields from instruction and most importantly shift them down
                    // to be adjacent so the instruction can match our table patterns.
                    context.methodVisitor.visitInsn(ICONST_0);
                    int offset = 0;
                    for (final MaskField maskField : maskFields) {
                        context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
                        context.methodVisitor.visitLdcInsn(maskField.asMask());
                        context.methodVisitor.visitInsn(IAND);
                        if (maskField.srcLSB > 0) {
                            context.methodVisitor.visitLdcInsn(maskField.srcLSB);
                            context.methodVisitor.visitInsn(IUSHR);
                            if (offset > 0) {
                                context.methodVisitor.visitLdcInsn(offset);
                                context.methodVisitor.visitInsn(ISHL);
                            }
                        }
                        context.methodVisitor.visitInsn(IOR);
                        offset += maskField.srcMSB - maskField.srcLSB + 1;
                    }
                }
                context.methodVisitor.visitTableSwitchInsn(tableMin, tableMax, defaultCase, labels);
            } else { // LookupSwitch
                // Java requires switch values to be in signed sorted order. We'll get visited in the original
                // order of the patterns in the node's pattern list (which we must not change), so we need to
                // pass a remapped labels array to the switch instruction.
                final int[] sortedPatterns = new int[caseCount];
                for (int i = 0; i < caseCount; i++) {
                    sortedPatterns[i] = patterns[i] & mask;
                }
                final Label[] labels = sortPatternsAndGetRemappedLabels(sortedPatterns);

                // For non-sequential patterns we have to create a lookup switch.
                context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
                context.methodVisitor.visitLdcInsn(mask);
                context.methodVisitor.visitInsn(IAND);
                context.methodVisitor.visitLookupSwitchInsn(defaultCase, sortedPatterns, labels);
            }
        }

        @Override
        public DecoderTreeVisitor visitSwitchCase(final int index, final int pattern) {
            context.methodVisitor.visitLabel(cases[index]);
            return new InnerNodeVisitor(context.withProcessed(switchMask), null);
        }

        @Override
        public void visitEnd() {
            context.methodVisitor.visitLabel(defaultCase);
            context.generateIllegalInstruction();

            popVariables();
        }

        private Label[] sortPatternsAndGetRemappedLabels(final int[] patterns) {
            final int caseCount = patterns.length;

            final PatternAndLabel[] compressedPatternsAndLabels = new PatternAndLabel[caseCount];
            for (int i = 0; i < caseCount; i++) {
                compressedPatternsAndLabels[i] = new PatternAndLabel(patterns[i], cases[i]);
            }

            Arrays.sort(compressedPatternsAndLabels);

            final Label[] labels = new Label[caseCount];
            for (int i = 0; i < caseCount; i++) {
                patterns[i] = compressedPatternsAndLabels[i].pattern;
                labels[i] = compressedPatternsAndLabels[i].label;
            }

            return labels;
        }
    }

    private static final class BranchVisitor extends LocalVariableOwner implements DecoderTreeBranchVisitor {
        public BranchVisitor(final GeneratorContext context) {
            super(context);
        }

        @Override
        public void visit(final int count, final DecoderTreeNodeFieldInstructionArguments arguments) {
            pushLocalVariables(arguments);
        }

        @Override
        public DecoderTreeVisitor visitBranchCase(final int index, final int mask, final int pattern) {
            final int remainingMask = mask & ~context.processedMask;
            if (remainingMask != 0) {
                final Label elseLabel = new Label();
                context.methodVisitor.visitVarInsn(ILOAD, context.localInst); // inst
                context.methodVisitor.visitLdcInsn(remainingMask); // inst, mask
                context.methodVisitor.visitInsn(IAND); // instMasked
                context.methodVisitor.visitLdcInsn(pattern & ~context.processedMask); // inst, pattern
                context.methodVisitor.visitJumpInsn(IF_ICMPNE, elseLabel);
                return new InnerNodeVisitor(context.withProcessed(remainingMask), elseLabel);
            } else {
                return new InnerNodeVisitor(context.withProcessed(remainingMask), null);
            }
        }

        @Override
        public void visitEnd() {
            context.generateIllegalInstruction();

            popVariables();
        }
    }

    private static class LeafVisitor implements DecoderTreeLeafVisitor, Opcodes {
        private final GeneratorContext context;

        public LeafVisitor(final GeneratorContext context) {
            this.context = context;
        }

        @Override
        public void visitInstruction(final InstructionDeclaration declaration) {
            if (declaration.type == InstructionType.ILLEGAL) {
                context.generateIllegalInstruction();
                return;
            }

            if (declaration.type == InstructionType.HINT) {
                context.generateInstOffsetInc(declaration.size);
                context.generateContinue();
                return;
            }

            final InstructionDefinition definition = R5Instructions.getDefinition(declaration);
            if (definition == null) {
                context.generateIllegalInstruction();
                return;
            }

            if (definition.readsPC) {
                context.generateSavePC();
            }

            context.methodVisitor.visitVarInsn(ALOAD, GeneratorContext.LOCAL_THIS);
            for (final InstructionArgument argument : definition.parameters) {
                if (argument instanceof ConstantInstructionArgument) {
                    final ConstantInstructionArgument constantArgument = (ConstantInstructionArgument) argument;
                    context.methodVisitor.visitLdcInsn(constantArgument.value);
                } else if (argument instanceof FieldInstructionArgument) {
                    final FieldInstructionArgument fieldArgument = (FieldInstructionArgument) argument;
                    if (context.localVariables.containsKey(fieldArgument)) {
                        final int localIndex = context.localVariables.getInt(fieldArgument);
                        context.methodVisitor.visitVarInsn(ILOAD, localIndex);
                    } else {
                        context.generateGetField(fieldArgument);
                    }
                } else {
                    throw new IllegalArgumentException();
                }
            } // cpu, arg0, ..., argN

            final String methodDescriptor = "(" + StringUtils.repeat('I', definition.parameters.length) + ")"
                                            + (definition.returnsBoolean ? "Z" : "V");
            context.methodVisitor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(R5CPUTemplate.class),
                    definition.methodName, methodDescriptor, false);

            if (definition.returnsBoolean) {
                assert context.isTopLevel;
                final Label updateOffsetAndContinueLabel = new Label();
                context.methodVisitor.visitJumpInsn(IFEQ, updateOffsetAndContinueLabel);
                if (!definition.writesPC) {
                    context.generateInstOffsetInc(declaration.size);
                    context.generateSavePC();
                }
                context.methodVisitor.visitInsn(RETURN);

                context.methodVisitor.visitLabel(updateOffsetAndContinueLabel);
                context.generateInstOffsetInc(declaration.size);
                context.generateContinue();
            } else if (definition.writesPC) {
                assert context.isTopLevel;
                context.methodVisitor.visitInsn(RETURN);
            } else {
                context.generateInstOffsetInc(declaration.size);
                context.generateContinue();
            }
        }

        @Override
        public void visitEnd() {
        }
    }

    private static abstract class LocalVariableOwner implements Opcodes {
        // This is our threshold for pulling field extraction out of individual instruction leaf nodes into
        // a switch or branch node. 0 = pull up everything used more than once, 1 = pull up nothing,
        // everything in-between is the relative value of used/total that has to be exceeded for a field
        // extraction to be pulled up.
        // The value of 0.5 has been empirically determined as roughly a sweet-spot for the time being.
        private static final float THRESHOLD = 0.5f;

        private final HashMap<FieldInstructionArgument, String> ownedLocals = new HashMap<>();
        private final Label beginVariableScopeLabel = new Label();

        protected final GeneratorContext context;

        protected LocalVariableOwner(final GeneratorContext context) {
            this.context = context;
        }

        protected void pushLocalVariables(final DecoderTreeNodeFieldInstructionArguments arguments) {
            final int threshold = Math.max(2, (int) (arguments.totalLeafCount * THRESHOLD));
            arguments.arguments.forEach((argument, entry) -> {
                if (entry.count >= threshold && !context.localVariables.containsKey(argument)) {
                    ownedLocals.put(argument, String.join("_", entry.names));
                }
            });

            if (ownedLocals.isEmpty()) {
                return;
            }

            for (final FieldInstructionArgument argument : ownedLocals.keySet()) {
                // This works because while it's a map, we fill and empty it like a stack.
                final int localIndex = context.localFirstField + context.localVariables.size();
                context.localVariables.put(argument, localIndex);

                context.generateGetField(argument);
                context.methodVisitor.visitVarInsn(ISTORE, localIndex);
            }

            context.methodVisitor.visitLabel(beginVariableScopeLabel);
        }

        protected void popVariables() {
            if (ownedLocals.isEmpty()) {
                return;
            }

            final Label endVariableScopeLabel = new Label();
            context.methodVisitor.visitLabel(endVariableScopeLabel);

            for (final Map.Entry<FieldInstructionArgument, String> entry : ownedLocals.entrySet()) {
                final FieldInstructionArgument argument = entry.getKey();
                final int localIndex = context.localVariables.removeInt(argument);
                context.methodVisitor.visitLocalVariable(entry.getValue(), "I", null, beginVariableScopeLabel, endVariableScopeLabel, localIndex);
            }
        }
    }

    private static final class PatternAndLabel implements Comparable<PatternAndLabel> {
        public final int pattern;
        public final Label label;

        private PatternAndLabel(final int pattern, final Label label) {
            this.pattern = pattern;
            this.label = label;
        }

        @Override
        public int compareTo(final PatternAndLabel o) {
            return Integer.compare(pattern, o.pattern);
        }
    }

    private static final class MaskField {
        public static ArrayList<MaskField> create(int mask) {
            final ArrayList<MaskField> maskFields = new ArrayList<>();
            int offset = 0;
            while (mask != 0) {
                final int lsb = Integer.numberOfTrailingZeros(mask);
                mask = mask >>> lsb;
                int msb = lsb - 1;
                while ((mask & 1) != 0) {
                    msb++;
                    mask = mask >>> 1;
                }
                maskFields.add(new MaskField(msb + offset, lsb + offset));
                offset += msb + 1;
            }
            return maskFields;
        }

        public final int srcMSB;
        public final int srcLSB;

        private MaskField(final int srcMSB, final int srcLSB) {
            this.srcMSB = srcMSB;
            this.srcLSB = srcLSB;
        }

        public int asMask() {
            return BitUtils.maskFromRange(srcLSB, srcMSB);
        }
    }
}
