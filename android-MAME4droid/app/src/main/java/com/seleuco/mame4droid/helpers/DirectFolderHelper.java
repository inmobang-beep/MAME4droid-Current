/*
 * This file is part of MAME4droid.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package com.seleuco.mame4droid.helpers;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import com.seleuco.mame4droid.MAME4droid;
import com.seleuco.mame4droid.R;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * "Shared folder" ROM storage: a plain public folder (/sdcard/MAME) read with
 * all-files access instead of a SAF tree. Meant for devices without a document
 * picker (car head units, some TV boxes) and for users who just want to drop
 * ROMs in with any file manager or a USB stick.
 *
 * The native side is unchanged: it still routes everything under the ROMs path
 * through the SAF callbacks, and this helper answers them from the filesystem.
 * Direct mode = a ROMs path is set but no SAF tree URI.
 */
public class DirectFolderHelper extends SAFHelper {
	private static final String TAG = "DirectFolder";
	public static final String FOLDER_NAME = "MAME";

	private final MAME4droid mm;
	private final Map<Integer, ArrayList<String[]>> openDirs = new HashMap<>();
	private final Map<Integer, Integer> dirPos = new HashMap<>();
	private int nextDirId = 1;

	public DirectFolderHelper(MAME4droid value) {
		super(value);
		mm = value;
	}

	public static boolean isDirect(MAME4droid mm) {
		String dir = mm.getPrefsHelper().getROMsDIR();
		return mm.getPrefsHelper().getSAF_Uri() == null && dir != null && dir.startsWith("/");
	}

	public static File sharedFolder() {
		return new File(Environment.getExternalStorageDirectory(), FOLDER_NAME);
	}

	/**
	 * All-files access (target 30+), or legacy storage plus the old storage
	 * permissions (target 29: one ordinary permission popup, which head units
	 * without the all-files settings screen still have).
	 */
	public static boolean hasAccess(MAME4droid mm) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) return true;
		return Environment.isExternalStorageLegacy()
			&& mm.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
			&& mm.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
	}

	private boolean direct() {
		return isDirect(mm);
	}

	private File file(String path) {
		return new File(mm.getPrefsHelper().getROMsDIR() + path);
	}

	// =========================================================================
	// SETUP FLOW
	// =========================================================================

	/** Chosen from the ROMs dialog: ask for all-files access if needed, then start. */
	public static void useSharedFolder(MAME4droid mm) {
		if (hasAccess(mm)) {
			start(mm);
			return;
		}
		if (Environment.isExternalStorageLegacy()) {
			mm.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
				Manifest.permission.WRITE_EXTERNAL_STORAGE}, MainHelper.REQUEST_CODE_ALL_FILES);
			return;
		}
		Uri pkg = Uri.parse("package:" + mm.getPackageName());
		try {
			mm.startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
				MainHelper.REQUEST_CODE_ALL_FILES);
			return;
		} catch (ActivityNotFoundException ignored) {}
		try {
			mm.startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
				MainHelper.REQUEST_CODE_ALL_FILES);
			return;
		} catch (ActivityNotFoundException ignored) {}

		// No settings screen for it either: tell how to grant it over adb.
		mm.getDialogHelper().showMessage(
			mm.getString(R.string.dlg_shared_no_settings, mm.getPackageName()),
			(dialog, which) -> mm.showDialog(DialogHelper.DIALOG_ROMs));
	}

	/** Back from the system permission screen or popup. */
	public static void permissionResult(MAME4droid mm) {
		if (hasAccess(mm)) {
			start(mm);
		} else {
			mm.getDialogHelper().showMessage(mm.getString(R.string.dlg_shared_denied),
				(dialog, which) -> mm.showDialog(DialogHelper.DIALOG_ROMs));
		}
	}

	private static void start(MAME4droid mm) {
		File root = sharedFolder();
		if (!root.isDirectory() && !root.mkdirs())
			Log.w(TAG, "cannot create " + root);

		mm.getMainHelper().setInstallationDirType(MainHelper.INSTALLATION_DIR_FILES_DIR);
		mm.getPrefsHelper().setSAF_Uri(null);
		mm.getPrefsHelper().setROMsDIR(root.getAbsolutePath());

		new Thread(mm::runMAME4droid).start();
	}

	// =========================================================================
	// SAF CALLBACKS ANSWERED FROM THE FILESYSTEM
	// =========================================================================

	@Override
	public ArrayList<String> getRomsFileNames() {
		if (!direct()) return super.getRomsFileNames();
		String[] names = file("/").list();
		if (names == null) return null;
		ArrayList<String> list = new ArrayList<>();
		for (String n : names) list.add(n);
		return list;
	}

	@Override
	public synchronized int readDir(String dirName) {
		if (!direct()) return super.readDir(dirName);
		File[] files = file(dirName).listFiles();
		if (files == null) return 0;
		ArrayList<String[]> entries = new ArrayList<>();
		for (File f : files) {
			boolean dir = f.isDirectory();
			entries.add(new String[]{f.getName(), String.valueOf(dir ? 0 : f.length()),
				String.valueOf(f.lastModified()), dir ? "D" : "F"});
		}
		int id = nextDirId++;
		openDirs.put(id, entries);
		dirPos.put(id, 0);
		return id;
	}

	@Override
	public synchronized int closeDir(int id) {
		if (!direct()) return super.closeDir(id);
		dirPos.remove(id);
		return openDirs.remove(id) != null ? 1 : 0;
	}

	@Override
	public synchronized String[] getNextDirEntry(int id) {
		if (!direct()) return super.getNextDirEntry(id);
		ArrayList<String[]> entries = openDirs.get(id);
		Integer pos = dirPos.get(id);
		if (entries == null || pos == null || pos >= entries.size()) return null;
		dirPos.put(id, pos + 1);
		return entries.get(pos);
	}

	@Override
	public int openUriFd(String pathName, String flags) {
		if (!direct()) return super.openUriFd(pathName, flags);
		File f = file(pathName);
		try {
			if (!flags.equals("r")) {
				File parent = f.getParentFile();
				if (parent != null && !parent.isDirectory()) parent.mkdirs();
			} else if (!f.isFile()) {
				return -1;
			}
			return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(flags)).detachFd();
		} catch (Exception e) {
			Log.w(TAG, "open " + f + " (" + flags + "): " + e);
			return -1;
		}
	}

	@Override
	public boolean deleteFile(String pathName) {
		if (!direct()) return super.deleteFile(pathName);
		return file(pathName).delete();
	}

	@Override
	public boolean initLazy() {
		return direct() || super.initLazy();
	}

	@Override
	public boolean listUriFiles(boolean reload) {
		return direct() || super.listUriFiles(reload);
	}

	@Override
	public void deleteCacheFile() {
		if (!direct()) super.deleteCacheFile();
	}

	@Override
	public void persistCacheIfDirty() {
		if (!direct()) super.persistCacheIfDirty();
	}
}
