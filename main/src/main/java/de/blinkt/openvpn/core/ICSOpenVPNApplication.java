/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

import android.os.StrictMode;
import android.os.strictmode.Violation;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.UUID;
import java.util.concurrent.Executors;

import de.blinkt.openvpn.BuildConfig;
import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.api.AppRestrictions;

public class ICSOpenVPNApplication extends Application {
    private StatusListener mStatus;
    public static final String AUTO_LOADED_PROFILE_UUID = "auto-loaded-profile-uuid";
    public static final String AUTO_LOADED_PROFILE_NAME = "Auto-Loaded VPN";

    @Override
    public void onCreate() {
        if (BuildConfig.BUILD_TYPE.equals("debug"))
            enableStrictModes();

        if("robolectric".equals(Build.FINGERPRINT))
            return;

        LocaleHelper.setDesiredLocale(this);
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            createNotificationChannels();
        mStatus = new StatusListener();
        mStatus.init(getApplicationContext());

        createFirstLaunchSetting();

        AppRestrictions.getInstance(this).checkRestrictions(this);

        autoLoadVpnProfile();
    }

    private void autoLoadVpnProfile() {
        try {
            VpnProfile existingProfile = ProfileManager.get(this, AUTO_LOADED_PROFILE_UUID);
            if (existingProfile != null) {
                return;
            }

            String configContent = readAssetFile("client.ovpn");
            if (configContent == null || configContent.trim().isEmpty()) {
                VpnStatus.logError("Auto-load: client.ovpn not found or empty");
                return;
            }

            ConfigParser cp = new ConfigParser();
            cp.parseConfig(new StringReader(configContent));
            VpnProfile vp = cp.convertProfile();

            vp.mName = AUTO_LOADED_PROFILE_NAME;
            vp.setUUID(UUID.fromString(AUTO_LOADED_PROFILE_UUID));
            vp.mProfileCreator = "auto-loader";
            vp.mUserEditable = true;

            ProfileManager pm = ProfileManager.getInstance(this);
            pm.addProfile(vp);
            pm.saveProfile(this, vp);
            pm.saveProfileList(this);

            VpnStatus.logInfo("Auto-load: VPN profile loaded successfully");

            VPNLaunchHelper.startOpenVpn(vp, getApplicationContext(), "Auto-connect", false);

        } catch (ConfigParser.ConfigParseError e) {
            VpnStatus.logError("Auto-load: Parse error - " + e.getMessage());
        } catch (IOException e) {
            VpnStatus.logError("Auto-load: IO error - " + e.getMessage());
        } catch (Exception e) {
            VpnStatus.logError("Auto-load: Error - " + e.getMessage());
        }
    }

    private String readAssetFile(String fileName) {
        StringBuilder content = new StringBuilder();
        try {
            InputStream is = getAssets().open(fileName);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            return content.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private void createFirstLaunchSetting() {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(this);
        long firstStart = prefs.getLong("firstStart", 0);
        if (firstStart == 0) {
            SharedPreferences.Editor pedit = prefs.edit();
            pedit.putLong("firstStart", System.currentTimeMillis());
            pedit.apply();
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.updateResources(base));
    }

    private void enableStrictModes() {
        StrictMode.ThreadPolicy.Builder tpbuilder = new StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog();

        StrictMode.VmPolicy.Builder vpbuilder = new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tpbuilder.penaltyListener(Executors.newSingleThreadExecutor(), this::logViolation);
            vpbuilder.penaltyListener(Executors.newSingleThreadExecutor(), this::logViolation);
        }

        StrictMode.VmPolicy policy = vpbuilder.build();
        StrictMode.setVmPolicy(policy);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LocaleHelper.onConfigurationChange(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    public void logViolation(Violation v) {
        String name = Application.getProcessName();
        System.err.println("------------------------- Violation detected in " + name + " ------" + v.getCause() + "---------------------------");
        VpnStatus.logException(VpnStatus.LogLevel.DEBUG, null, v);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        CharSequence name = getString(R.string.channel_name_background);
        NotificationChannel mChannel = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_BG_ID,
                name, NotificationManager.IMPORTANCE_MIN);

        mChannel.setDescription(getString(R.string.channel_description_background));
        mChannel.enableLights(false);
        mChannel.setLightColor(Color.DKGRAY);
        mNotificationManager.createNotificationChannel(mChannel);

        name = getString(R.string.channel_name_status);
        mChannel = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID,
                name, NotificationManager.IMPORTANCE_LOW);

        mChannel.setDescription(getString(R.string.channel_description_status));
        mChannel.enableLights(true);
        mChannel.setLightColor(Color.BLUE);
        mNotificationManager.createNotificationChannel(mChannel);

        name = getString(R.string.channel_name_userreq);
        mChannel = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID,
                name, NotificationManager.IMPORTANCE_HIGH);
        mChannel.setDescription(getString(R.string.channel_description_userreq));
        mChannel.enableVibration(true);
        mChannel.setLightColor(Color.CYAN);
        mNotificationManager.createNotificationChannel(mChannel);
    }
}
