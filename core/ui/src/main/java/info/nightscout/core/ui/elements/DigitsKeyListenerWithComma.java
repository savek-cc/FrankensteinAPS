package info.nightscout.core.ui.elements;

import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.NumberKeyListener;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

class DigitsKeyListenerWithComma extends NumberKeyListener {

    /**
     * The characters that are used.
     *
     * @see KeyEvent#getMatch
     * @see #getAcceptedChars
     */
    private static final char[][] CHARACTERS = new char[][]{
            new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'},
            new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-'},
            new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ','},
            new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '.', ','},
    };

    private char[] mAccepted;
    private final boolean mSign;
    private final boolean mDecimal;

    private static final int SIGN = 1;
    private static final int DECIMAL = 2;

    private static final DigitsKeyListenerWithComma[] sInstance = new DigitsKeyListenerWithComma[4];

    @Override @NonNull
    protected char[] getAcceptedChars() {
        return mAccepted;
    }

    /**
     * Allocates a DigitsKeyListener that accepts the digits 0 through 9.
     */
    public DigitsKeyListenerWithComma() {
        this(false, false);
    }

    /**
     * Allocates a DigitsKeyListener that accepts the digits 0 through 9,
     * plus the minus sign (only at the beginning) and/or decimal point
     * (only one per field) if specified.
     */
    public DigitsKeyListenerWithComma(boolean sign, boolean decimal) {
        mSign = sign;
        mDecimal = decimal;

        int kind = (sign ? SIGN : 0) | (decimal ? DECIMAL : 0);
        mAccepted = CHARACTERS[kind];
    }

    /**
     * Returns a DigitsKeyListener that accepts the digits 0 through 9.
     */
    public static DigitsKeyListenerWithComma getInstance() {
        return getInstance(false, false);
    }

    /**
     * Returns a DigitsKeyListener that accepts the digits 0 through 9,
     * plus the minus sign (only at the beginning) and/or decimal point
     * (only one per field) if specified.
     */
    public static DigitsKeyListenerWithComma getInstance(boolean sign, boolean decimal) {
        int kind = (sign ? SIGN : 0) | (decimal ? DECIMAL : 0);

        if (sInstance[kind] != null)
            return sInstance[kind];

        sInstance[kind] = new DigitsKeyListenerWithComma(sign, decimal);
        return sInstance[kind];
    }

    /**
     * Returns a DigitsKeyListener that accepts only the characters
     * that appear in the specified String.  Note that not all characters
     * may be available on every keyboard.
     */
    public static DigitsKeyListenerWithComma getInstance(String accepted) {
        // TODO: do we need a cache of these to avoid allocating?

        DigitsKeyListenerWithComma dim = new DigitsKeyListenerWithComma();

        dim.mAccepted = new char[accepted.length()];
        accepted.getChars(0, accepted.length(), dim.mAccepted, 0);

        return dim;
    }

    public int getInputType() {
        int contentType = InputType.TYPE_CLASS_NUMBER;
        if (mSign) {
            contentType |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        }
        if (mDecimal) {
            contentType |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
        }
        return contentType;
    }

    @Override
    public CharSequence filter(CharSequence source, int start, int end,
                               Spanned dest, int dstart, int dend) {
        CharSequence out = super.filter(source, start, end, dest, dstart, dend);

        if (!mSign && !mDecimal) {
            return out;
        }

        if (out != null) {
            source = out;
            start = 0;
            end = out.length();
        }

        int sign = -1;
        int decimal = -1;
        int dlen = dest.length();

        /*
         * Find out if the existing text has '-' or '.' characters.
         */

        for (int i = 0; i < dstart; i++) {
            char c = dest.charAt(i);

            if (c == '-') {
                sign = i;
            } else if (c == '.' || c == ',') {
                decimal = i;
            }
        }
        for (int i = dend; i < dlen; i++) {
            char c = dest.charAt(i);

            if (c == '-') {
                return "";    // Nothing can be inserted in front of a '-'.
            } else if (c == '.' || c == ',') {
                decimal = i;
            }
        }

        /*
         * If it does, we must strip them out from the source.
         * In addition, '-' must be the very first character,
         * and nothing can be inserted before an existing '-'.
         * Go in reverse order so the offsets are stable.
         */

        SpannableStringBuilder stripped = null;

        for (int i = end - 1; i >= start; i--) {
            char c = source.charAt(i);
            boolean strip = false;

            if (c == '-') {
                if (i != start || dstart != 0) {
                    strip = true;
                } else if (sign >= 0) {
                    strip = true;
                } else {
                    sign = i;
                }
            } else if (c == '.' || c == ',') {
                if (decimal >= 0) {
                    strip = true;
                } else {
                    decimal = i;
                }
            }

            if (strip) {
                if (end == start + 1) {
                    return "";  // Only one character, and it was stripped.
                }

                if (stripped == null) {
                    stripped = new SpannableStringBuilder(source, start, end);
                }

                stripped.delete(i - start, i + 1 - start);
            }
        }

        if (stripped != null) {
            return stripped;
        } else return out;
    }
}