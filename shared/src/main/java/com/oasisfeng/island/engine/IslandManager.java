package com.oasisfeng.island.engine;

import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.support.annotation.RequiresApi;

import com.oasisfeng.android.util.Apps;
import com.oasisfeng.island.util.DevicePolicies;
import com.oasisfeng.island.util.Hacks;
import com.oasisfeng.island.util.OwnerUser;
import com.oasisfeng.island.util.Permissions;
import com.oasisfeng.island.util.ProfileUser;

import java.util.List;
import java.util.Objects;

import javax.annotation.ParametersAreNonnullByDefault;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.LOLLIPOP_MR1;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;

/**
 * Utilities of shared basic functionality for modules
 *
 * Created by Oasis on 2017/2/20.
 */
@ParametersAreNonnullByDefault
public class IslandManager {

	public static boolean ensureLegacyInstallNonMarketAppAllowed(final Context context, final DevicePolicies policies) {
		policies.clearUserRestrictionsIfNeeded(context, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
		if (SDK_INT >= O) return true;		// INSTALL_NON_MARKET_APPS is no longer supported since Android O.

		final ContentResolver resolver = context.getContentResolver();
		@SuppressWarnings("deprecation") final String INSTALL_NON_MARKET_APPS = Settings.Secure.INSTALL_NON_MARKET_APPS;
		if (Settings.Secure.getInt(resolver, INSTALL_NON_MARKET_APPS, 0) > 0) return true;
		if (SDK_INT < LOLLIPOP_MR1) {		// INSTALL_NON_MARKET_APPS is not whitelisted by DPM.setSecureSetting() until Android 5.1.
			if (! Permissions.has(context, WRITE_SECURE_SETTINGS)) return false;
			Settings.Secure.putInt(resolver, INSTALL_NON_MARKET_APPS, 1);
		} else policies.execute(DevicePolicyManager::setSecureSetting, INSTALL_NON_MARKET_APPS, "1");
		return Settings.Secure.getInt(resolver, INSTALL_NON_MARKET_APPS, 0) > 0;
	}

	@OwnerUser @ProfileUser public static boolean ensureAppHiddenState(final Context context, final String pkg, final boolean state) {
		final DevicePolicies policies = new DevicePolicies(context);
		if (policies.invoke(DevicePolicyManager::setApplicationHidden, pkg, state)) return true;
		// Since setApplicationHidden() return false if already in that state, also check the current state.
		final boolean hidden = policies.invoke(DevicePolicyManager::isApplicationHidden, pkg);
		return state == hidden;
	}

	@OwnerUser @ProfileUser public static String ensureAppFreeToLaunch(final Context context, final String pkg) {
		final DevicePolicies policies = new DevicePolicies(context);
		if (policies.invoke(DevicePolicyManager::isApplicationHidden, pkg)) {		// Hidden or not installed
			if (! policies.invoke(DevicePolicyManager::setApplicationHidden, pkg, false))
				if (! Apps.of(context).isInstalledInCurrentUser(pkg)) return "not_installed";	// Not installed in profile, just give up.
		}
		if (SDK_INT >= N) try {
			if (policies.isPackageSuspended(pkg))
				policies.invoke(DevicePolicyManager::setPackagesSuspended, new String[] { pkg }, false);
		} catch (final PackageManager.NameNotFoundException ignored) { return "not_found"; }
		return null;
	}

	@OwnerUser public static boolean launchApp(final Context context, final String pkg, final UserHandle profile) {
		final LauncherApps launcher_apps = Objects.requireNonNull((LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE));
		final List<LauncherActivityInfo> activities = launcher_apps.getActivityList(pkg, profile);
		if (activities == null || activities.isEmpty()) return false;
		launcher_apps.startMainActivity(activities.get(0).getComponentName(), profile, null, null);
		return true;
	}

	/** @return profile ID, or 0 if none */
	@RequiresApi(N) public static int getManagedProfileIdIncludingDisabled(final Context context) {
		if (Hacks.UserManager_getProfileIds != null) {
			final int[] profiles = Hacks.UserManager_getProfileIds.invoke(Process.myUserHandle().hashCode(), false)
					.on(Objects.requireNonNull(context.getSystemService(UserManager.class)));
			final int current_user = Process.myUserHandle().hashCode();
			for (final int profile : profiles)
				if (profile != current_user) return profile;            		// Only one managed profile is supported by Android at present.
			return 0;
		} else {	// Fallback to profiles without disabled.
			final List<UserHandle> profiles = Objects.requireNonNull(context.getSystemService(UserManager.class)).getUserProfiles();
			final UserHandle current_user = Process.myUserHandle();
			for (final UserHandle profile : profiles)
				if (! profile.equals(current_user)) return profile.hashCode();	// Only one managed profile is supported by Android at present.
			return 0;
		}
	}
}
