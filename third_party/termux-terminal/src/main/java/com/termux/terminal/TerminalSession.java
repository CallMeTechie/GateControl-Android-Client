/*
 * Copyright (C) Termux contributors (termux/termux-app, terminal-emulator), derived from
 * Terminal Emulator for Android (jackpal/Android-Terminal-Emulator).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by GateControl (2026): the terminal of a remote shell instead of a local process. No JNI,
 * no subprocess, no pseudo terminal and no threads: the bytes of the remote side come in through
 * append(byte[], int, int) on the main thread, everything the person types goes out through
 * {@link Remote#send(byte[], int, int)}, a new size through {@link Remote#resize(int, int)}. A copy
 * to the clipboard asked for by the remote side (OSC 52) is told apart from the person's own copy.
 */
package com.termux.terminal;

import java.util.UUID;

/**
 * A terminal session of a remote shell, coupled to a terminal interface.
 * <p>
 * Terminal emulation begins when the size is made known by a call to {@link #updateSize(int, int, int, int)}.
 * All terminal emulation and callback methods are performed on the main thread.
 * <p>
 * NOTE: The terminal session may outlive the view, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    /** The remote side of this session. */
    public interface Remote {
        /** Bytes the person typed or the emulator answered, to the remote shell. */
        void send(byte[] data, int offset, int count);

        /** The terminal has a new size (columns × rows). */
        void resize(int columns, int rows);

        /** The remote side asked to put text on the clipboard (OSC 52). */
        void onRemoteClipboard(String text);
    }

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Buffer to translate code points into utf8 before writing them. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when the screen or the title changes. */
    TerminalSessionClient mClient;

    private final Remote mRemote;

    /** False once {@link #finish()} was called. */
    private boolean mRunning = true;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    private final Integer mTranscriptRows;

    /** The output of the emulator: its answers go to the remote side, a remote copy is told apart. */
    private final TerminalOutput mEmulatorOutput = new TerminalOutput() {
        @Override
        public void write(byte[] data, int offset, int count) {
            TerminalSession.this.write(data, offset, count);
        }

        @Override
        public void titleChanged(String oldTitle, String newTitle) {
            TerminalSession.this.titleChanged(oldTitle, newTitle);
        }

        @Override
        public void onCopyTextToClipboard(String text) {
            mRemote.onRemoteClipboard(text);
        }

        @Override
        public void onPasteTextFromClipboard() {
            TerminalSession.this.onPasteTextFromClipboard();
        }

        @Override
        public void onBell() {
            TerminalSession.this.onBell();
        }

        @Override
        public void onColorsChanged() {
            TerminalSession.this.onColorsChanged();
        }
    };

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, Remote remote) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mRemote = remote;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Tell the remote side the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        mRemote.resize(columns, rows);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(mEmulatorOutput, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
    }

    /** Bytes of the remote side: feed them to the emulator (main thread). */
    public void append(byte[] data, int offset, int count) {
        if (mEmulator == null || count <= 0) return;
        if (offset == 0) {
            mEmulator.append(data, count);
        } else {
            byte[] part = new byte[count];
            System.arraycopy(data, offset, part, 0, count);
            mEmulator.append(part, count);
        }
        notifyScreenUpdate();
    }

    /** Write data to the remote shell. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (isRunning() && count > 0) mRemote.send(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** End this session: nothing is sent to the remote side any more. */
    public synchronized void finish() {
        mRunning = false;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

}
