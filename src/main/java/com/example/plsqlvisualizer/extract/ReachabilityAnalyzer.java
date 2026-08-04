package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.model.Reachability;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Works out whether a statement always runs, or sits under a branch or a loop.
 *
 * <p>PL/Scope does not model control flow: an {@code IF} creates no context row,
 * so a conditional write is indistinguishable from an unconditional one in the
 * dictionary alone. The IR also wants the branch's {@code guard} text, which
 * only exists in the source. So this pass reads {@code USER_SOURCE} back and
 * matches block keywords.
 *
 * <p>This is a brace matcher, not a PL/SQL parser (design.md §2.1) — it tracks
 * {@code IF}/{@code LOOP} openers against their {@code END} and nothing else.
 * Comments and string literals are blanked first so keywords inside them cannot
 * open a phantom block. Where it is unsure it stays silent and the caller falls
 * back to Oracle's own ITERATOR signal.
 */
public class ReachabilityAnalyzer {

    private enum BlockKind { IF, LOOP }

    private record Block(BlockKind kind, int startLine, int endLine, String guard) {
    }

    private record Token(String word, int line, int col) {
    }

    private final List<Block> blocks;

    /**
     * Innermost block per source line, as an index into {@link #blocks}, or -1.
     *
     * <p>Built once instead of searched per statement. Scanning every block for
     * every statement is quadratic in the size of a unit, and a generated package
     * body of twenty thousand lines makes that the slowest thing in the
     * extraction — the blocks are known up front, so the answer may as well be.
     */
    private final int[] innermostByLine;

    public ReachabilityAnalyzer(List<String> sourceLines) {
        this.blocks = findBlocks(sourceLines);
        this.innermostByLine = indexByLine(blocks, sourceLines.size());
    }

    /**
     * Stamps each block across the lines it spans, outermost first. Blocks nest,
     * so a block opening later is inside the one before it and its stamp is the
     * one that should survive — sorting by start line makes overwriting do that
     * for free.
     */
    private static int[] indexByLine(List<Block> blocks, int lineCount) {
        int lastLine = lineCount;
        for (Block block : blocks) {
            lastLine = Math.max(lastLine, block.endLine());
        }
        int[] index = new int[lastLine + 1];
        Arrays.fill(index, -1);

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> blocks.get(i).startLine()));

        for (int i : order) {
            Block block = blocks.get(i);
            for (int line = Math.max(1, block.startLine());
                 line <= Math.min(lastLine, block.endLine()); line++) {
                index[line] = i;
            }
        }
        return index;
    }

    /**
     * Reachability of a statement on {@code line}.
     *
     * @param insideIterator Oracle's ITERATOR signal from the usage-context walk.
     *        It is authoritative for FOR loops, so it wins when the source scan
     *        found nothing.
     */
    public Reachability at(int line, boolean insideIterator) {
        Block innermost = innermostContaining(line);
        if (innermost != null) {
            return innermost.kind() == BlockKind.LOOP
                    ? Reachability.LOOP
                    : Reachability.BRANCH_CONDITIONAL;
        }
        return insideIterator ? Reachability.LOOP : Reachability.UNCONDITIONAL;
    }

    /** The branch condition guarding {@code line}, or null when it is not in an IF. */
    public String guardAt(int line) {
        Block innermost = innermostContaining(line);
        return innermost != null && innermost.kind() == BlockKind.IF ? innermost.guard() : null;
    }

    private Block innermostContaining(int line) {
        if (line < 1 || line >= innermostByLine.length) {
            return null;
        }
        int at = innermostByLine[line];
        return at < 0 ? null : blocks.get(at);
    }

    private static List<Block> findBlocks(List<String> rawLines) {
        List<String> cleaned = blankCommentsAndStrings(rawLines);
        List<Token> tokens = tokenize(cleaned);
        List<Block> found = new ArrayList<>();

        // Open blocks, innermost last. Each entry remembers where it started so
        // the matching END can close the full line range.
        List<Object[]> open = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String word = token.word();

            if (word.equals("END")) {
                String next = i + 1 < tokens.size() ? tokens.get(i + 1).word() : "";
                BlockKind closing = switch (next) {
                    case "IF" -> BlockKind.IF;
                    case "LOOP" -> BlockKind.LOOP;
                    default -> null;
                };
                // A bare END closes a subprogram or a CASE expression — not our concern.
                if (closing != null && !open.isEmpty()) {
                    int last = open.size() - 1;
                    if (open.get(last)[0] == closing) {
                        Object[] block = open.remove(last);
                        found.add(new Block(closing, (int) block[1], token.line(), (String) block[2]));
                    }
                }
                continue;
            }

            // ELSIF is a single token, so it never matches here — an ELSIF branch
            // stays part of the IF block that is already open, which is what we want.
            if (word.equals("IF")) {
                String guard = readGuard(rawLines, cleaned, tokens, i);
                open.add(new Object[] {BlockKind.IF, token.line(), guard});
            } else if (word.equals("LOOP")) {
                open.add(new Object[] {BlockKind.LOOP, token.line(), null});
            }
        }

        return found;
    }

    /**
     * Reconstructs {@code IF <condition>} from the original source, so the guard
     * shown in the IR reads exactly as written (string literals included).
     */
    private static String readGuard(List<String> rawLines, List<String> cleaned,
                                    List<Token> tokens, int ifIndex) {
        Token ifToken = tokens.get(ifIndex);
        for (int i = ifIndex + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.word().equals("THEN")) {
                return sliceSource(rawLines, ifToken.line(), ifToken.col(), token.line(), token.col());
            }
            // A THEN should follow closely; if we ran past the next block keyword
            // the source is shaped in a way we do not model, so give up quietly.
            if (token.word().equals("IF") || token.word().equals("LOOP") || token.word().equals("END")) {
                return null;
            }
        }
        return null;
    }

    /** Original text from (startLine, startCol) up to but excluding (endLine, endCol). */
    private static String sliceSource(List<String> lines, int startLine, int startCol,
                                      int endLine, int endCol) {
        StringBuilder sb = new StringBuilder();
        for (int line = startLine; line <= endLine; line++) {
            String text = lineAt(lines, line);
            int from = line == startLine ? Math.min(startCol, text.length()) : 0;
            int to = line == endLine ? Math.min(endCol, text.length()) : text.length();
            if (from < to) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text, from, to);
            }
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    private static String lineAt(List<String> lines, int line) {
        int index = line - 1;
        return index >= 0 && index < lines.size() ? lines.get(index) : "";
    }

    /**
     * Replaces comment and string-literal characters with spaces, keeping every
     * line the same length. Positions therefore stay identical to the original,
     * which is what lets {@link #sliceSource} quote the real text back.
     */
    private static List<String> blankCommentsAndStrings(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        boolean inBlockComment = false;

        for (String raw : lines) {
            char[] chars = raw.toCharArray();
            boolean inString = false;
            for (int i = 0; i < chars.length; i++) {
                if (inBlockComment) {
                    boolean end = chars[i] == '*' && i + 1 < chars.length && chars[i + 1] == '/';
                    chars[i] = ' ';
                    if (end) {
                        chars[i + 1] = ' ';
                        i++;
                        inBlockComment = false;
                    }
                } else if (inString) {
                    boolean quote = chars[i] == '\'';
                    chars[i] = ' ';
                    if (quote) {
                        inString = false;
                    }
                } else if (chars[i] == '-' && i + 1 < chars.length && chars[i + 1] == '-') {
                    for (int j = i; j < chars.length; j++) {
                        chars[j] = ' ';
                    }
                    break;
                } else if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '*') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i++;
                    inBlockComment = true;
                } else if (chars[i] == '\'') {
                    chars[i] = ' ';
                    inString = true;
                }
            }
            out.add(new String(chars));
        }
        return out;
    }

    private static List<Token> tokenize(List<String> cleaned) {
        List<Token> tokens = new ArrayList<>();
        for (int index = 0; index < cleaned.size(); index++) {
            String text = cleaned.get(index);
            int line = index + 1;
            int i = 0;
            while (i < text.length()) {
                if (!isWordChar(text.charAt(i))) {
                    i++;
                    continue;
                }
                int start = i;
                while (i < text.length() && isWordChar(text.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(text.substring(start, i).toUpperCase(), line, start));
            }
        }
        return tokens;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }
}
