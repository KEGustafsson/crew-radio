# R8 rules for the release build, after proguard-android-optimize.txt (AGP's defaults: enum
# values()/valueOf, Parcelable creators, native methods, the components the manifest names —
# MainActivity, StatusActivity, SettingsActivity, PttService, CallService — and the consumer rules
# the AndroidX and Material libraries ship). The app itself uses no reflection.

# Names are kept: a crash trace from a phone at sea must read as the source does, without a
# mapping file to fetch and match to the build. Shrinking and optimisation still remove what is
# unused; app/build/outputs/mapping/release/usage.txt lists what went.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
