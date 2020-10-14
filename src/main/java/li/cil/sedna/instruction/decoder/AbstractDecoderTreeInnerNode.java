package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.FieldInstructionArgument;
import li.cil.sedna.instruction.InstructionDeclaration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public abstract class AbstractDecoderTreeInnerNode extends AbstractDecoderTreeNode {
    public final AbstractDecoderTreeNode[] children;
    private final int maxDepth;
    private final int mask;
    private final int pattern;

    AbstractDecoderTreeInnerNode(final AbstractDecoderTreeNode[] children) {
        this.children = children;

        int maxDepth = 0;
        for (final AbstractDecoderTreeNode child : children) {
            maxDepth = Math.max(maxDepth, child.getMaxDepth());
        }
        this.maxDepth = 1 + maxDepth;

        int mask = children[0].getMask();
        for (int i = 1; i < children.length; i++) {
            final AbstractDecoderTreeNode child = children[i];
            mask &= child.getMask();
        }
        this.mask = mask;

        this.pattern = children[0].getPattern() & mask;
    }

    @Override
    public int getMaxDepth() {
        return maxDepth;
    }

    @Override
    public int getMask() {
        return mask;
    }

    @Override
    public int getPattern() {
        return pattern;
    }

    @Override
    public DecoderTreeNodeFieldInstructionArguments getArguments() {
        int totalLeafCount = 0;
        final HashMap<FieldInstructionArgument, ArrayList<DecoderTreeNodeFieldInstructionArguments.Entry>> childEntries = new HashMap<>();
        for (final AbstractDecoderTreeNode child : children) {
            final DecoderTreeNodeFieldInstructionArguments childArguments = child.getArguments();
            totalLeafCount += childArguments.totalLeafCount;
            childArguments.arguments.forEach((argument, entry) -> {
                childEntries.computeIfAbsent(argument, arg -> new ArrayList<>()).add(entry);
            });
        }

        final HashMap<FieldInstructionArgument, DecoderTreeNodeFieldInstructionArguments.Entry> entries = new HashMap<>();
        childEntries.forEach((argument, childEntriesForArgument) -> {
            int count = 0;
            final HashSet<String> names = new HashSet<>();
            for (final DecoderTreeNodeFieldInstructionArguments.Entry entry : childEntriesForArgument) {
                count += entry.count;
                names.addAll(entry.names);
            }
            entries.put(argument, new DecoderTreeNodeFieldInstructionArguments.Entry(count, new ArrayList<>(names)));
        });

        return new DecoderTreeNodeFieldInstructionArguments(totalLeafCount, entries);
    }
}