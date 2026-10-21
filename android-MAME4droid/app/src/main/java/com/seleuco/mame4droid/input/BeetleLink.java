/*
 * This file is part of MAME4droid.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package com.seleuco.mame4droid.input;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.seleuco.mame4droid.Emulator;
import com.seleuco.mame4droid.MAME4droid;
import com.seleuco.mame4droid.helpers.DialogHelper;

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Direct link to the Beetle Pad app (com.beetle.pad) for cars whose USB gamepad is
 * cut off from the Android input system. Beetle Pad reads the pad over USB and serves
 * its state on 127.0.0.1:5611; we feed it straight into player 1, bypassing
 * Android input entirely.
 *
 * One 16-byte big-endian frame per change: buttons int (SDL button-number bits),
 * then lx ly rx ry lt rt as shorts. The current state is sent on connect.
 *
 * While linked, events from Beetle Pad's own virtual HID pad ("Beetle Pad ...") are
 * swallowed so the same pad does not drive player 1 twice.
 */
public final class BeetleLink implements Runnable {
	private static final String TAG = "BeetleLink";
	private static final int PORT = 5611;
	private static final String VIRTUAL_PAD_NAME = "Beetle Pad";

	// SDL button numbers
	private static final int A = 0, B = 1, X = 2, Y = 3, BACK = 4, GUIDE = 5, START = 6,
		L3 = 7, R3 = 8, LB = 9, RB = 10, UP = 11, DOWN = 12, LEFT = 13, RIGHT = 14;

	private static final int TRIGGER_ON = 16000;

	private static volatile MAME4droid mm;
	private static volatile boolean started;
	private static volatile boolean connected;
	// the link is up as soon as the Beetle Pad app runs, pad or no pad: count
	// the pad as present once something on it has been pressed or moved
	private static volatile boolean active;
	private static volatile boolean foreground = true;
	private static final Handler ui = new Handler(Looper.getMainLooper());
	private static final Instrumentation instr = new Instrumentation();

	private int lastBtn;
	private boolean lastLt, lastRt;

	public static void start(MAME4droid value) {
		mm = value;
		if (started) return;
		started = true;
		Thread th = new Thread(new BeetleLink(), "beetle-link");
		th.setDaemon(true);
		th.start();
	}

	/** True once the linked pad has been used (touch controls can hide). */
	public static boolean isConnected() {
		return connected && active;
	}

	/** Activity resumed/paused: input is only fed while we are in front. */
	public static void setForeground(boolean value) {
		foreground = value;
		if (!value) feed(0, 0f, 0f, 0f, 0f, 0f, 0f);
	}

	/** True for events from Beetle Pad's virtual pad while the direct link is up. */
	public static boolean isShadowed(InputDevice device) {
		return connected && device != null && device.getName() != null
			&& device.getName().startsWith(VIRTUAL_PAD_NAME);
	}

	@Override
	public void run() {
		byte[] buf = new byte[16];
		while (true) {
			Socket s = new Socket();
			try {
				s.connect(new InetSocketAddress("127.0.0.1", PORT), 1000);
				s.setTcpNoDelay(true);
				DataInputStream in = new DataInputStream(s.getInputStream());
				setConnected(true);
				Log.w(TAG, "link up");
				while (true) {
					in.readFully(buf);
					apply(buf);
				}
			} catch (Throwable t) {
				if (connected) Log.w(TAG, "link down " + t);
			} finally {
				try { s.close(); } catch (Throwable ignored) {}
			}
			if (connected) {
				lastBtn = 0;
				lastLt = lastRt = false;
				feed(0, 0f, 0f, 0f, 0f, 0f, 0f);
				setConnected(false);
			}
			try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
		}
	}

	private static void setConnected(boolean value) {
		connected = value;
		if (!value) active = false;
		ui.post(() -> {
			MAME4droid m = mm;
			if (m != null && m.getMainHelper() != null && m.getEmuView() != null) m.getMainHelper().updateMAME4droid();
		});
	}

	private static int s16(byte[] b, int i) {
		return (short) (((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF));
	}

	private static boolean on(int btn, int bit) {
		return (btn >> bit & 1) != 0;
	}

	private void apply(byte[] b) {
		int btn = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
		float lx = s16(b, 4) / 32768f, ly = s16(b, 6) / 32768f;
		float rx = s16(b, 8) / 32768f, ry = s16(b, 10) / 32768f;
		int ltRaw = s16(b, 12), rtRaw = s16(b, 14);
		boolean lt = ltRaw > TRIGGER_ON, rt = rtRaw > TRIGGER_ON;

		if (!foreground) {
			lastBtn = btn;
			return;
		}
		if (!active && (btn != 0 || Math.abs(lx) > 0.3f || Math.abs(ly) > 0.3f || lt || rt)) {
			active = true;
			setConnected(true); // refresh the touch controls
		}

		int pressed = btn & ~lastBtn;
		lastBtn = btn;
		lastLt = lt;
		lastRt = rt;

		// The options dialog is an Android dialog: drive it with key events instead.
		if (Emulator.isInOptions()) {
			if (on(pressed, UP)) key(KeyEvent.KEYCODE_DPAD_UP);
			if (on(pressed, DOWN)) key(KeyEvent.KEYCODE_DPAD_DOWN);
			if (on(pressed, LEFT)) key(KeyEvent.KEYCODE_DPAD_LEFT);
			if (on(pressed, RIGHT)) key(KeyEvent.KEYCODE_DPAD_RIGHT);
			if (on(pressed, A)) key(KeyEvent.KEYCODE_DPAD_CENTER);
			if (on(pressed, B) || on(pressed, GUIDE)) key(KeyEvent.KEYCODE_BACK);
			return;
		}

		if (on(pressed, GUIDE)) {
			openOptions();
			return;
		}

		// SELECT + START together leaves the game
		if (on(btn, BACK) && on(btn, START) && (on(pressed, BACK) || on(pressed, START))) {
			exitGame();
			return;
		}

		// Same layout as the Xbox profile in GameController (A/B swapped for arcade order)
		int m = 0;
		if (on(btn, A)) m |= IController.BTN2_VALUE;
		if (on(btn, B)) m |= IController.BTN1_VALUE;
		if (on(btn, X)) m |= IController.BTN3_VALUE;
		if (on(btn, Y)) m |= IController.BTN4_VALUE;
		if (on(btn, RB)) m |= IController.BTN5_VALUE;
		if (on(btn, LB)) m |= IController.BTN6_VALUE;
		if (lt) m |= IController.BTN7_VALUE;
		if (rt) m |= IController.BTN8_VALUE;
		if (on(btn, START) || on(btn, L3)) m |= IController.START_VALUE;
		if (on(btn, BACK) || on(btn, R3)) m |= IController.COIN_VALUE;
		// D-pad plus the left stick as directions (most arcade games are digital)
		if (on(btn, UP) || ly < -0.5f) m |= IController.UP_VALUE;
		if (on(btn, DOWN) || ly > 0.5f) m |= IController.DOWN_VALUE;
		if (on(btn, LEFT) || lx < -0.5f) m |= IController.LEFT_VALUE;
		if (on(btn, RIGHT) || lx > 0.5f) m |= IController.RIGHT_VALUE;

		feed(m, lx, ly, rx, ry, Math.max(ltRaw, 0) / 32767f, Math.max(rtRaw, 0) / 32767f);
	}

	private static void feed(int bits, float lx, float ly, float rx, float ry, float lt, float rt) {
		Emulator.setDigitalData(0, bits);
		Emulator.setAnalogData(Emulator.LEFT_STICK_DATA, 0, lx, -ly);
		Emulator.setAnalogData(Emulator.RIGHT_STICK_DATA, 0, rx, -ry);
		Emulator.setAnalogData(Emulator.TRIGGER_DATA, 0, (rt * 2.0f) - 1.0f, (lt * 2.0f) - 1.0f);
	}

	private static void openOptions() {
		feed(0, 0f, 0f, 0f, 0f, 0f, 0f);
		ui.post(() -> {
			MAME4droid m = mm;
			if (m == null || Emulator.isInOptions()) return;
			GameController.rememberOptionKey(null);
			Emulator.setInOptions(true);
			m.showDialog(DialogHelper.DIALOG_OPTIONS);
		});
	}

	private static void exitGame() {
		feed(0, 0f, 0f, 0f, 0f, 0f, 0f);
		Emulator.setValue(Emulator.EXIT_GAME, 1);
		try { Thread.sleep(InputHandler.PRESS_WAIT); } catch (InterruptedException ignored) {}
		Emulator.setValue(Emulator.EXIT_GAME, 0);
	}

	/** Injects a key into our own window (allowed without INJECT_EVENTS); never on the UI thread. */
	private static void key(int code) {
		try {
			instr.sendKeyDownUpSync(code);
		} catch (Throwable t) {
			Log.w(TAG, "key " + code + " " + t);
		}
	}
}
