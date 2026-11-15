package com.onessh.app.term;

import java.util.ArrayList;

/**
 * 终端屏幕模型：一个 rows × cols 的字符网格 + 回滚缓冲。
 *
 * 每个单元格记 4 个整数（码点、前景、背景、属性），用 int[] 而不是对象，
 * 50×200 的屏幕 + 2000 行回滚也不会把内存撑爆。
 */
public final class TerminalBuffer {

    public static final int DEFAULT_FG = -1;
    public static final int DEFAULT_BG = -2;

    public static final int ATTR_BOLD = 1;
    public static final int ATTR_ITALIC = 1 << 1;
    public static final int ATTR_UNDERLINE = 1 << 2;
    public static final int ATTR_INVERSE = 1 << 3;
    public static final int ATTR_DIM = 1 << 4;
    public static final int ATTR_STRIKE = 1 << 5;
    /** 宽字符占的第二格 */
    public static final int ATTR_WIDE_TAIL = 1 << 6;

    public static final class Line {
        final int[] cp;
        final int[] fg;
        final int[] bg;
        final int[] at;

        Line(int cols) {
            cp = new int[cols];
            fg = new int[cols];
            bg = new int[cols];
            at = new int[cols];
            clear(DEFAULT_FG, DEFAULT_BG);
        }

        Line(Line src, int cols) {
            this(cols);
            int n = Math.min(src.cp.length, cols);
            System.arraycopy(src.cp, 0, cp, 0, n);
            System.arraycopy(src.fg, 0, fg, 0, n);
            System.arraycopy(src.bg, 0, bg, 0, n);
            System.arraycopy(src.at, 0, at, 0, n);
        }

        void clear(int fgColor, int bgColor) {
            for (int i = 0; i < cp.length; i++) {
                cp[i] = ' ';
                fg[i] = fgColor;
                bg[i] = bgColor;
                at[i] = 0;
            }
        }
    }

    public int cols;
    public int rows;
    private Line[] lines;
    private Line[] altLines;
    private boolean alt;

    private final ArrayList<Line> scrollback = new ArrayList<>();
    public static final int MAX_SCROLLBACK = 2000;

    public int cursorRow;
    public int cursorCol;
    public boolean cursorVisible = true;
    public boolean wrapPending;

    public int scrollTop;
    public int scrollBottom;

    public boolean originMode;
    public boolean autowrap = true;

    public int curFg = DEFAULT_FG;
    public int curBg = DEFAULT_BG;
    public int curAttr;

    private int savedRow;
    private int savedCol;
    private int savedFg;
    private int savedBg;
    private int savedAttr;

    public TerminalBuffer(int cols, int rows) {
        this.cols = Math.max(2, cols);
        this.rows = Math.max(2, rows);
        lines = new Line[this.rows];
        for (int i = 0; i < this.rows; i++) lines[i] = new Line(this.cols);
        scrollTop = 0;
        scrollBottom = this.rows - 1;
    }

    public Line line(int row) {
        return lines[row];
    }

    public int scrollbackSize() {
        return scrollback.size();
    }

    public Line scrollbackAt(int i) {
        if (i < 0 || i >= scrollback.size()) return null;
        return scrollback.get(i);
    }

    /* ------------------------------------------------------------- 尺寸 */

    public void resize(int newCols, int newRows) {
        newCols = Math.max(2, newCols);
        newRows = Math.max(2, newRows);
        if (newCols == cols && newRows == rows) return;

        Line[] next = new Line[newRows];
        int keep = Math.min(newRows, rows);
        for (int i = 0; i < keep; i++) next[i] = new Line(lines[i], newCols);
        for (int i = keep; i < newRows; i++) next[i] = new Line(newCols);

        if (alt) {
            altLines = next;
        } else {
            lines = next;
        }
        cols = newCols;
        rows = newRows;
        scrollTop = 0;
        scrollBottom = rows - 1;
        cursorRow = Math.min(cursorRow, rows - 1);
        cursorCol = Math.min(cursorCol, cols - 1);
        wrapPending = false;
    }

    /* ------------------------------------------------------------- 光标 */

    public void setCursor(int row, int col) {
        int r = row;
        int c = col;
        if (originMode) {
            r += scrollTop;
            r = Math.max(scrollTop, Math.min(scrollBottom, r));
        } else {
            r = Math.max(0, Math.min(rows - 1, r));
        }
        cursorRow = r;
        cursorCol = Math.max(0, Math.min(cols - 1, c));
        wrapPending = false;
    }

    public void moveCursor(int dRow, int dCol) {
        setCursor(cursorRow + dRow, cursorCol + dCol);
    }

    public void saveCursor() {
        savedRow = cursorRow;
        savedCol = cursorCol;
        savedFg = curFg;
        savedBg = curBg;
        savedAttr = curAttr;
    }

    public void restoreCursor() {
        cursorRow = Math.max(0, Math.min(rows - 1, savedRow));
        cursorCol = Math.max(0, Math.min(cols - 1, savedCol));
        curFg = savedFg;
        curBg = savedBg;
        curAttr = savedAttr;
        wrapPending = false;
    }

    /* ------------------------------------------------------------- 输出 */

    public void putChar(int ch) {
        int w = width(ch);
        if (w == 0) return;

        if (wrapPending && autowrap) {
            wrapPending = false;
            cursorCol = 0;
            lineFeed();
        }
        if (cursorCol + w > cols) {
            if (autowrap) {
                cursorCol = 0;
                lineFeed();
            } else {
                cursorCol = Math.max(0, cols - w);
            }
        }

        Line l = lines[cursorRow];
        l.cp[cursorCol] = ch;
        l.fg[cursorCol] = curFg;
        l.bg[cursorCol] = curBg;
        l.at[cursorCol] = curAttr;

        if (w == 2) {
            if (cursorCol + 1 < cols) {
                l.cp[cursorCol + 1] = 0;
                l.fg[cursorCol + 1] = curFg;
                l.bg[cursorCol + 1] = curBg;
                l.at[cursorCol + 1] = curAttr | ATTR_WIDE_TAIL;
            }
        }

        cursorCol += w;
        if (cursorCol >= cols) {
            cursorCol = cols - 1;
            wrapPending = true;
        }
    }

    public void lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1);
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
        wrapPending = false;
    }

    public void reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown(1);
        } else if (cursorRow > 0) {
            cursorRow--;
        }
        wrapPending = false;
    }

    public void carriageReturn() {
        cursorCol = 0;
        wrapPending = false;
    }

    public void backspace() {
        if (cursorCol > 0) {
            cursorCol--;
        } else if (wrapPending) {
            wrapPending = false;
        }
        wrapPending = false;
    }

    public void tab() {
        int next = ((cursorCol / 8) + 1) * 8;
        cursorCol = Math.min(cols - 1, next);
    }

    public void backTab() {
        int prev = ((Math.max(0, cursorCol - 1)) / 8) * 8;
        cursorCol = Math.max(0, prev);
    }

    /* ------------------------------------------------------------- 滚动 */

    public void scrollUp(int n) {
        for (int k = 0; k < n; k++) {
            Line top = lines[scrollTop];
            boolean fullScreen = scrollTop == 0 && scrollBottom == rows - 1;
            if (fullScreen) {
                scrollback.add(new Line(top, cols));
                while (scrollback.size() > MAX_SCROLLBACK) scrollback.remove(0);
            }
            for (int i = scrollTop; i < scrollBottom; i++) lines[i] = lines[i + 1];
            lines[scrollBottom] = new Line(cols);
            lines[scrollBottom].clear(curFg, curBg);
        }
    }

    public void scrollDown(int n) {
        for (int k = 0; k < n; k++) {
            for (int i = scrollBottom; i > scrollTop; i--) lines[i] = lines[i - 1];
            lines[scrollTop] = new Line(cols);
            lines[scrollTop].clear(curFg, curBg);
        }
    }

    public void setScrollRegion(int top, int bottom) {
        int t = Math.max(0, top);
        int b = Math.min(rows - 1, bottom);
        if (t >= b) return;
        scrollTop = t;
        scrollBottom = b;
        setCursor(originMode ? 0 : 0, 0);
    }

    /* ------------------------------------------------------------- 擦除 */

    /** @param mode 0=光标到末尾 1=开头到光标 2=整屏 3=整屏并清回滚 */
    public void eraseInDisplay(int mode) {
        if (mode == 3) {
            scrollback.clear();
            for (Line l : lines) l.clear(curFg, curBg);
            return;
        }
        if (mode == 2) {
            for (Line l : lines) l.clear(curFg, curBg);
            return;
        }
        if (mode == 0) {
            eraseInLine(0);
            for (int r = cursorRow + 1; r < rows; r++) lines[r].clear(curFg, curBg);
        } else if (mode == 1) {
            eraseInLine(1);
            for (int r = 0; r < cursorRow; r++) lines[r].clear(curFg, curBg);
        }
    }

    /** @param mode 0=光标到行尾 1=行首到光标 2=整行 */
    public void eraseInLine(int mode) {
        Line l = lines[cursorRow];
        int from = 0;
        int to = cols - 1;
        if (mode == 0) from = cursorCol;
        else if (mode == 1) to = cursorCol;
        for (int i = from; i <= to && i < cols; i++) {
            l.cp[i] = ' ';
            l.fg[i] = curFg;
            l.bg[i] = curBg;
            l.at[i] = 0;
        }
    }

    public void eraseChars(int n) {
        Line l = lines[cursorRow];
        for (int i = cursorCol; i < Math.min(cols, cursorCol + n); i++) {
            l.cp[i] = ' ';
            l.fg[i] = curFg;
            l.bg[i] = curBg;
            l.at[i] = 0;
        }
    }

    /* ------------------------------------------------- 插入 / 删除 字符行 */

    public void insertChars(int n) {
        Line l = lines[cursorRow];
        int count = Math.min(n, cols - cursorCol);
        System.arraycopy(l.cp, cursorCol, l.cp, cursorCol + count, cols - cursorCol - count);
        System.arraycopy(l.fg, cursorCol, l.fg, cursorCol + count, cols - cursorCol - count);
        System.arraycopy(l.bg, cursorCol, l.bg, cursorCol + count, cols - cursorCol - count);
        System.arraycopy(l.at, cursorCol, l.at, cursorCol + count, cols - cursorCol - count);
        for (int i = 0; i < count; i++) {
            int p = cursorCol + i;
            l.cp[p] = ' ';
            l.fg[p] = curFg;
            l.bg[p] = curBg;
            l.at[p] = 0;
        }
    }

    public void deleteChars(int n) {
        Line l = lines[cursorRow];
        int count = Math.min(n, cols - cursorCol);
        System.arraycopy(l.cp, cursorCol + count, l.cp, cursorCol, cols - cursorCol - count);
        System.arraycopy(l.fg, cursorCol + count, l.fg, cursorCol, cols - cursorCol - count);
        System.arraycopy(l.bg, cursorCol + count, l.bg, cursorCol, cols - cursorCol - count);
        System.arraycopy(l.at, cursorCol + count, l.at, cursorCol, cols - cursorCol - count);
        for (int i = 0; i < count; i++) {
            int p = cols - count + i;
            l.cp[p] = ' ';
            l.fg[p] = curFg;
            l.bg[p] = curBg;
            l.at[p] = 0;
        }
    }

    public void insertLines(int n) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return;
        int count = Math.min(n, scrollBottom - cursorRow + 1);
        for (int i = scrollBottom; i >= cursorRow + count; i--) lines[i] = lines[i - count];
        for (int i = 0; i < count; i++) {
            lines[cursorRow + i] = new Line(cols);
            lines[cursorRow + i].clear(curFg, curBg);
        }
    }

    public void deleteLines(int n) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return;
        int count = Math.min(n, scrollBottom - cursorRow + 1);
        for (int i = cursorRow; i <= scrollBottom - count; i++) lines[i] = lines[i + count];
        for (int i = 0; i < count; i++) {
            lines[scrollBottom - i] = new Line(cols);
            lines[scrollBottom - i].clear(curFg, curBg);
        }
    }

    /* ------------------------------------------------------------- 备用屏 */

    public void enterAltScreen() {
        if (alt) return;
        alt = true;
        altLines = lines;
        Line[] fresh = new Line[rows];
        for (int i = 0; i < rows; i++) fresh[i] = new Line(cols);
        lines = fresh;
        saveCursor();
        cursorRow = 0;
        cursorCol = 0;
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    public void exitAltScreen() {
        if (!alt) return;
        alt = false;
        lines = altLines != null ? altLines : new Line[rows];
        altLines = null;
        restoreCursor();
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    public boolean isAltScreen() {
        return alt;
    }

    /* ------------------------------------------------------------- 重置 */

    public void reset() {
        alt = false;
        altLines = null;
        curFg = DEFAULT_FG;
        curBg = DEFAULT_BG;
        curAttr = 0;
        cursorRow = 0;
        cursorCol = 0;
        cursorVisible = true;
        wrapPending = false;
        autowrap = true;
        originMode = false;
        scrollTop = 0;
        scrollBottom = rows - 1;
        for (Line l : lines) l.clear(DEFAULT_FG, DEFAULT_BG);
    }

    /* ------------------------------------------------------------- 文本 */

    /** 把整块屏幕（不含回滚）拼成文本，用于「复制全部」 */
    public String allText() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            sb.append(rowText(lines[r]));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 按“回滚区 + 当前屏”的统一行号取一行文本，越界返回 null；长按复制用 */
    public String lineTextAt(int index) {
        Line l = lineAtIndex(index);
        return l == null ? null : rowText(l);
    }

    /** 按统一行号取行，越界返回 null */
    public Line lineAtIndex(int index) {
        int sb = scrollbackSize();
        if (index < 0) return null;
        if (index < sb) return scrollbackAt(index);
        int r = index - sb;
        if (r < 0 || r >= rows) return null;
        return lines[r];
    }

    /**
     * 取起止单元格之间的文本（起止都是闭区间，含起止格）。
     * 跨行时行与行之间补一个换行；每行去掉行尾空格。
     * 起点落在宽字符的第二格时回退到它的第一格，保证宽字符整个进或出。
     */
    public String textInRange(int startIndex, int startCol, int endIndex, int endCol) {
        if (startIndex > endIndex
                || (startIndex == endIndex && startCol > endCol)) {
            int ti = startIndex;
            int tc = startCol;
            startIndex = endIndex;
            startCol = endCol;
            endIndex = ti;
            endCol = tc;
        }
        StringBuilder sb = new StringBuilder();
        for (int index = startIndex; index <= endIndex; index++) {
            Line l = lineAtIndex(index);
            if (l == null) continue;
            int from = index == startIndex ? startCol : 0;
            int to = index == endIndex ? endCol : cols - 1;
            from = Math.max(0, Math.min(cols - 1, from));
            to = Math.max(0, Math.min(cols - 1, to));
            if (from > to) continue;
            // 起点在宽字符的第二格：回退到第一格，字符整个算进来
            if ((l.at[from] & ATTR_WIDE_TAIL) != 0 && from > 0) from--;
            int last = -1;
            for (int i = from; i <= to; i++) {
                if (l.cp[i] != ' ' || l.bg[i] != DEFAULT_BG) last = i;
            }
            for (int i = from; i <= last; i++) {
                if ((l.at[i] & ATTR_WIDE_TAIL) != 0) continue;
                int c = l.cp[i];
                sb.appendCodePoint(c == 0 ? ' ' : c);
            }
            if (index < endIndex) sb.append('\n');
        }
        return sb.toString();
    }

    private String rowText(Line l) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (int i = 0; i < cols; i++) {
            if (l.cp[i] != ' ' || l.bg[i] != DEFAULT_BG) last = i;
        }
        for (int i = 0; i <= last; i++) {
            if ((l.at[i] & ATTR_WIDE_TAIL) != 0) continue;
            int c = l.cp[i];
            sb.appendCodePoint(c == 0 ? ' ' : c);
        }
        return sb.toString();
    }

    /* --------------------------------------------------------- 字符宽度 */

    /** 东亚宽字符占两格（wcwidth 的常用近似） */
    public static int width(int cp) {
        if (cp == 0) return 1;
        if (cp < 32 || (cp >= 0x7F && cp < 0xA0)) return 0;
        if (cp >= 0x0300 && cp <= 0x036F) return 0;
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) return 0;
        if (cp >= 0x1100 && (
                cp <= 0x115F
                        || cp == 0x2329 || cp == 0x232A
                        || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                        || (cp >= 0xAC00 && cp <= 0xD7A3)
                        || (cp >= 0xF900 && cp <= 0xFAFF)
                        || (cp >= 0xFE10 && cp <= 0xFE19)
                        || (cp >= 0xFE30 && cp <= 0xFE6F)
                        || (cp >= 0xFF00 && cp <= 0xFF60)
                        || (cp >= 0xFFE0 && cp <= 0xFFE6)
                        || (cp >= 0x1F300 && cp <= 0x1F64F)
                        || (cp >= 0x1F900 && cp <= 0x1F9FF)
                        || (cp >= 0x20000 && cp <= 0x3FFFD))) {
            return 2;
        }
        return 1;
    }
}
