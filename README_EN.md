# Activity Monitor

[简体中文](README.md) | English

Activity Monitor is an Android app that lets you see the system and user apps running on your phone, along with the CPU, memory, and device temperature they are using.

During everyday use, an Android phone can occasionally overheat or become sluggish. Apart from restarting it, there is often not much you can do. Some phones can temporarily turn on "Super power saving" for a few minutes to restrict background apps and services, and then turn it off again. But the option is not easy to find, and not every device supports it.

Activity Monitor shows which apps are using the CPU, which ones are consuming memory, and how hot the phone is at a glance. Once you find the culprit, you can stop, freeze, or disable it directly. It can also help you spot apps that quietly start in the background.

## Features

- View system apps and services separately from user apps.
- See each app's CPU and memory usage and running state.
- View battery temperature and other device status information.
- Stop running apps.
- Tap the three-dot menu or long-press an app to freeze, disable, or restore it.
- Use the app in Chinese or English, with System, Light, and Dark appearance modes.

> Stopping system apps may cause system instability, app crashes, or data loss. Do not stop system apps unless you fully understand the consequences.

## Why extra authorization is required

Android is not exactly eager to hand over its background app list. A regular app can normally see only its own little corner of the system. Want to see how much CPU or memory another app is using? Sorry, that information is not available. Want to reliably stop other apps? You usually do not have permission for that either.

Activity Monitor therefore needs a one-time ADB authorization to wake up its built-in privileged service. The service runs with the same identity as an ADB debugging session, which gives it access to complete data and lets it perform actions such as stopping apps.

- Root is not required.
- You do not need to keep the phone connected to a computer; after authorization, the app works normally.

Put simply, without authorization this app is nearly useless: the interface opens, but much of the CPU, memory, temperature, and other key data will be missing, and stopping other apps generally will not work. In the end, you will mostly just be looking at a list of apps.

## How to authorize the app

### Option 1: Your phone supports wireless debugging

This option is intended for devices running Android 11 or later that still include Wireless debugging.

> Note: setup is a little cumbersome, but once configured, it remains valid until the phone restarts.

1. Enable Developer options and Wireless debugging in your phone's settings.
2. Open Activity Monitor and tap "Start privileged service" in the top-right menu.
3. Select "Wireless pairing (no computer)".
4. Follow the app's instructions and select "Pair device with pairing code" on the system Wireless debugging screen.
5. Pull down the notification shade and enter the pairing port and six-digit pairing code shown on the screen.
6. Wait for the app to pair and start the privileged service.

Some manufacturers remove Wireless debugging from their systems. If the app reports "This device does not support wireless debugging", use Option 2 below.

### Option 2: Run one ADB command from a computer (recommended for developers)

This option works with all devices that support USB debugging, including phones without wireless debugging.

> Note: this method is better suited to developers, or to phones that do not have wireless debugging.

1. Enable Developer options and USB debugging in your phone's settings.
2. Connect the phone to a computer with a USB cable and allow USB debugging on the phone.
3. Open "Start privileged service" in Activity Monitor and tap "Copy computer command".
4. Paste the command into a terminal on the computer and run it.
5. Return to Activity Monitor and tap "Check again".

The computer must have [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) installed, including `adb`.

## Usage

- On first launch, if the privileged service has not been started, the interface shows a notice that functionality is limited.
- Pull down the list or tap the refresh button in the title bar to update app status.
- Tap the "End" button on the right side of an app to stop it.
- Tap the three-dot button on the right side of a user app, or long-press the app, to freeze, disable, or restore it.
- The privileged service expires after the device restarts and must be authorized again.
- Some apps are automatically restarted by the system after being stopped. This is normal Android behavior.

## FAQ

### Why do CPU, memory, or temperature keep showing "—"?

Most likely, the privileged service is still asleep. Follow the authorization steps above to wake it up, then pull down to refresh.

### Why does stopping an app have no effect?

Without authorization, the system mostly gives your stop request the cold shoulder. At best, it allows a regular app to clear some cached background processes. If you really need to stop an app without authorization, the more reliable option is to open the app's details page and tap "Force stop".

### Why do I need to authorize again after every restart?

You can blame Android for this one. For security reasons, the system closes debugging authorizations and wireless debugging connections after a device restart. The app cannot bypass this restriction.

### Does this app require Root?

No. You can use the full feature set after authorizing through wireless debugging or ADB from a computer. On rooted devices, Root access can also be used as an additional option.

### Does the app upload my data?

All data reading and privileged service operations are performed locally on the device. The app does not include functionality to upload device data to a server.

## Compatibility

- Android 11 or later is required.
- Settings and available features vary between manufacturers, so some interfaces and operation paths may differ.

## License

This project is open source under the [MIT License](LICENSE). You are free to use, modify, and distribute the project, provided that the original copyright notice and license notice are retained.

The wireless ADB pairing capability refers to LADB's wireless pairing implementation and includes related components. See [THIRD_PARTY_LADB_LICENSE.txt](THIRD_PARTY_LADB_LICENSE.txt) for its license notice.

## Support development

If this project has been helpful to you, you can scan the code below to buy the developer a coffee. Your support goes toward ongoing maintenance and new features.

<p align="center">
  <img src="pic/3f39201cc48ccf77698346a82a29a20a.png" alt="Support Activity Monitor development" width="360" />
</p>
