plugins {
    id("com.android.application")
}

android {
    namespace = "app.mockgps"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mockgps"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so a release build installs without extra setup.
            // Swap in your own signing config before distributing it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Declaring ACCESS_MOCK_LOCATION is the whole point of this app.
        disable += "MockLocation"
        abortOnError = false
    }
}

dependencies {
    // Lets the service also feed Google Play Services' fused location provider,
    // which most apps (maps, ride-hailing, games) read from.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
}
