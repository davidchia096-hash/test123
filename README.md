# Mock GPS

An Android app that sets your phone's GPS to any spot on the map, or walks a route at the speed you choose. Use it to test location-based apps without leaving your desk.

- **Point mode**: tap the map, search a place, or paste coordinates (`1.2834, 103.8607` or a maps link). Tap **Start mocking**. Tap elsewhere and press **Move here** to teleport.
- **Google Maps search**: tap **Google Maps** in the search bar, or pick *Search Google Maps for “…”* in the results. Search any shop, restaurant or landmark, tap it, then **Use this place**.
- **Share from Google Maps**: in the Google Maps app, open a place → **Share** → **Mock GPS**. The pin drops there automatically.
- **Route mode**: tap to add waypoints, drag to adjust, and long-press to remove. Pick Walk, Run, Bike, Car or a custom km/h value. **Loop** repeats the route, and **Pause** freezes you in place.
- **Drift**: adds a few metres of natural GPS jitter, so the position doesn't look pinned.
- **Saved places**: star a pin to keep it, then reach it later from the ★ button.
- Keeps running in the background, with a notification that has Stop and Pause buttons.
- Feeds Android's GPS, network and fused providers, plus Google Play Services' fused location. Both older and newer apps see the fake position.

Android 7.0+ (API 24). No API keys needed. The main map uses OpenStreetMap. Place search uses Google Maps' own website, opened inside the app, plus OpenStreetMap's Nominatim.

---

## 1. Get the APK

### Option A: build it on GitHub (no computer needed)

1. Create a new GitHub repository and upload this project's files into it (keep the folder structure).
2. The included workflow (`.github/workflows/build-apk.yml`) runs on every push.
3. Open the **Actions** tab, then the latest **Build APK** run. Under **Artifacts**, download **MockGPS-apk**. It's a zip that contains `app-debug.apk`.

### Option B: Android Studio

1. **File → Open** and pick this folder. Let Gradle sync; Android Studio may suggest updating the Gradle plugin, which is fine.
2. Plug in your phone with USB debugging on and press **Run**. Or use **Build → Build APK(s)**.

Command line: `./gradlew assembleDebug`. The APK ends up in `app/build/outputs/apk/debug/`.

## 2. Install and set up on the phone (one time)

1. Open the APK and allow **Install unknown apps** for your browser or file manager when asked.
2. **Unlock Developer options**: go to Settings → About phone and tap **Build number** 7 times. On Samsung it's under Software information.
3. Go to Settings → System → **Developer options** → **Select mock location app** and choose **Mock GPS**.
4. Open Mock GPS and allow location and notifications when asked. Location permission is required for Android to allow a location foreground service.

The app shows a yellow "One-time setup" card until step 3 is done. Its **Open settings** button takes you to the right screen.

## 3. Use it

| Want to… | Do this |
|---|---|
| Fake one spot | Point tab → tap the map → **Start mocking** |
| Find a shop or landmark | **Google Maps** button → search → tap the place → **Use this place** |
| Jump somewhere else | Tap a new spot → **Move here** |
| Simulate a walk or drive | Route tab → tap 2+ waypoints → pick a speed → **Start route** |
| Go back to the real GPS | **Stop** (in the app or the notification) |

Stopping removes the test providers, and Android returns to the real GPS straight away.

## Good to know

- Android marks mocked fixes (`Location.isMock()`). Apps that check it, such as some banking, attendance and game apps, can detect it and may refuse to work.
- On phones without Google Play Services, the platform providers are still mocked. The Play Services step is skipped.
- Map tiles © OpenStreetMap contributors. OpenStreetMap search uses the public Nominatim service, which is meant for light personal use, so it searches only when you press Enter.
- Google Maps search shows Google's normal website and reads the selected place's coordinates from the page address. It doesn't use Google's paid Places API, so it may need an update if Google changes its site.
- Each GitHub build is signed with a fresh debug key. To install a newer build over an older one, uninstall the old one first.
- `release` builds are signed with the debug key so they install as-is. Add your own signing config before you share the app.

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/app/mockgps/
    MainActivity.java         WebView host + JavaScript bridge, permissions, setup checks, shared links
    GoogleMapsPickerActivity.java  Google Maps screen for picking places of interest
    MapsLinkParser.java       Reads coordinates from Google Maps links / geo: URIs (unit-tested)
    MockLocationService.java  Foreground service that pushes fake fixes (point / route / drift)
    MockState.java            Shared state the UI observes
  assets/
    index.html, app.css, app.js   Map UI (Leaflet 1.9.4, bundled)
    leaflet/                       Leaflet library + licence (BSD-2-Clause)
  res/                        Icons, theme, strings
```

The UI is a local web page. You can open `app/src/main/assets/index.html` in a desktop browser to tweak it, since it runs with a stand-in bridge. Add `?setup` to the URL to see the setup card.
