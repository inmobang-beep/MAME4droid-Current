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
	/** 2P: 비틀 패드 1.33 부터 두 번째 패드를 같은 규격으로 이 창구에 보낸다. */
	private static final int PORT_2P = 5613;
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
	/** 이 창구가 붙어 있다(1P 의 [connected] 와 따로, 2P 에도 쓴다). */
	private volatile boolean up;

	private int lastBtn;
	private boolean lastLt, lastRt;
	/** 창구와 그 입력이 들어갈 MAME 플레이어(0 = 1P, 1 = 2P). */
	private final int port, player;

	private BeetleLink(int port, int player) {
		this.port = port;
		this.player = player;
	}

	public static void start(MAME4droid value) {
		mm = value;
		if (started) return;
		started = true;
		for (BeetleLink l : new BeetleLink[]{new BeetleLink(PORT, 0), new BeetleLink(PORT_2P, 1)}) {
			Thread th = new Thread(l, "beetle-link-" + (l.player + 1) + "p");
			th.setDaemon(true);
			th.start();
		}
	}

	/** True once the linked pad has been used (touch controls can hide). */
	public static boolean isConnected() {
		return connected && active;
	}

	/** Activity resumed/paused: input is only fed while we are in front. */
	public static void setForeground(boolean value) {
		foreground = value;
		if (!value) { feed(0, 0, 0f, 0f, 0f, 0f, 0f, 0f); feed(1, 0, 0f, 0f, 0f, 0f, 0f, 0f); }
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
				s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
				s.setTcpNoDelay(true);
				DataInputStream in = new DataInputStream(s.getInputStream());
				up = true;
				if (player == 0) setConnected(true);
				Log.w(TAG, "link up " + (player + 1) + "P");
				while (true) {
					in.readFully(buf);
					apply(buf);
				}
			} catch (Throwable t) {
				if (up) Log.w(TAG, "link down " + (player + 1) + "P " + t);
			} finally {
				try { s.close(); } catch (Throwable ignored) {}
			}
			if (up) {
				up = false;
				lastBtn = 0;
				lastLt = lastRt = false;
				feed(player, 0, 0f, 0f, 0f, 0f, 0f, 0f);
				if (player == 0) setConnected(false);
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
		// 2P 는 게임 입력만: 옵션 창·나가기·터치 조작 숨기기는 1P 몫
		if (player == 0 && !active && (btn != 0 || Math.abs(lx) > 0.3f || Math.abs(ly) > 0.3f || lt || rt)) {
			active = true;
			setConnected(true); // refresh the touch controls
		}

		int pressed = btn & ~lastBtn;
		lastBtn = btn;
		lastLt = lt;
		lastRt = rt;

		// The options dialog is an Android dialog: drive it with key events instead.
		if (player == 1 && Emulator.isInOptions()) return;
		if (Emulator.isInOptions()) {
			if (on(pressed, UP)) key(KeyEvent.KEYCODE_DPAD_UP);
			if (on(pressed, DOWN)) key(KeyEvent.KEYCODE_DPAD_DOWN);
			if (on(pressed, LEFT)) key(KeyEvent.KEYCODE_DPAD_LEFT);
			if (on(pressed, RIGHT)) key(KeyEvent.KEYCODE_DPAD_RIGHT);
			if (on(pressed, A)) key(KeyEvent.KEYCODE_DPAD_CENTER);
			if (on(pressed, B) || on(pressed, GUIDE)) key(KeyEvent.KEYCODE_BACK);
			return;
		}

		if (player == 0 && on(pressed, GUIDE)) {
			openOptions();
			return;
		}

		// SELECT + START together leaves the game
		if (player == 0 && on(btn, BACK) && on(btn, START) && (on(pressed, BACK) || on(pressed, START))) {
			exitGame();
			return;
		}

		// 패드 A B X Y RB LB LT RT → 게임 버튼([loadLayout], 게임룸에서 게임마다 정한 배치)
		int m = 0;
		boolean[] pad = {on(btn, A), on(btn, B), on(btn, X), on(btn, Y), on(btn, RB), on(btn, LB), lt, rt};
		int[] l = layout;
		for (int i = 0; i < pad.length; i++)
			if (pad[i] && l[i] >= 1 && l[i] <= 8) m |= GAME_BTN[l[i]];
		if (on(btn, START) || on(btn, L3)) m |= IController.START_VALUE;
		if (on(btn, BACK) || on(btn, R3)) m |= IController.COIN_VALUE;
		// D-pad plus the left stick as directions (most arcade games are digital)
		if (on(btn, UP) || ly < -0.5f) m |= IController.UP_VALUE;
		if (on(btn, DOWN) || ly > 0.5f) m |= IController.DOWN_VALUE;
		if (on(btn, LEFT) || lx < -0.5f) m |= IController.LEFT_VALUE;
		if (on(btn, RIGHT) || lx > 0.5f) m |= IController.RIGHT_VALUE;

		feed(player, m, lx, ly, rx, ry, Math.max(ltRaw, 0) / 32767f, Math.max(rtRaw, 0) / 32767f);
	}

	/**
	 * [player] 의 입력. 엔진은 2P 가 동전(SELECT)을 한 번 넣는 순간부터 2P 를 따로 센다
	 * (그 전에는 1P 입력이 모두에게 갈 수 있다 — myosd_droid_setDigitalData).
	 */
	private static void feed(int player, int bits, float lx, float ly, float rx, float ry, float lt, float rt) {
		Emulator.setDigitalData(player, bits);
		Emulator.setAnalogData(Emulator.LEFT_STICK_DATA, player, lx, -ly);
		Emulator.setAnalogData(Emulator.RIGHT_STICK_DATA, player, rx, -ry);
		Emulator.setAnalogData(Emulator.TRIGGER_DATA, player, (rt * 2.0f) - 1.0f, (lt * 2.0f) - 1.0f);
	}

	private static void openOptions() {
		feed(0, 0, 0f, 0f, 0f, 0f, 0f, 0f);
		ui.post(() -> {
			MAME4droid m = mm;
			if (m == null || Emulator.isInOptions()) return;
			GameController.rememberOptionKey(null);
			Emulator.setInOptions(true);
			m.showDialog(DialogHelper.DIALOG_OPTIONS);
		});
	}

	/** 패드 A B X Y RB LB LT RT → 게임 버튼 1~8(0 = 없음). 기본은 GameController 의 엑스박스 배치와 같다. */
	private static final int[] DEFAULT_LAYOUT = {2, 1, 3, 4, 5, 6, 7, 8};
	private static final String[] PAD_NAMES = {"A", "B", "X", "Y", "RB", "LB", "LT", "RT"};
	private static final int[] GAME_BTN = {0, IController.BTN1_VALUE, IController.BTN2_VALUE, IController.BTN3_VALUE,
		IController.BTN4_VALUE, IController.BTN5_VALUE, IController.BTN6_VALUE, IController.BTN7_VALUE, IController.BTN8_VALUE};
	private static volatile int[] layout = DEFAULT_LAYOUT;

	/** 게임룸이 정해 둔 그 게임의 배치(files/buttons/<세트>.txt), 없으면 기본. */
	public static void loadLayout(android.content.Context ctx, String game) {
		int[] l = DEFAULT_LAYOUT.clone();
		java.io.File f = game == null ? null : new java.io.File(ctx.getFilesDir(), "buttons/" + game + ".txt");
		if (f != null && f.isFile()) {
			try {
				String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
				for (String part : text.trim().split("\\s+")) {
					String[] kv = part.split("=");
					if (kv.length != 2) continue;
					for (int i = 0; i < PAD_NAMES.length; i++)
						if (PAD_NAMES[i].equals(kv[0])) {
							int n = Integer.parseInt(kv[1].trim());
							if (n >= 0 && n <= 8) l[i] = n;
						}
				}
				Log.w(TAG, "buttons for " + game + ": " + text.trim());
			} catch (Exception e) { Log.w(TAG, "buttons " + f + ": " + e); }
		}
		layout = l;
	}

	private static void exitGame() {
		feed(0, 0, 0f, 0f, 0f, 0f, 0f, 0f);
		feed(1, 0, 0f, 0f, 0f, 0f, 0f, 0f);
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
