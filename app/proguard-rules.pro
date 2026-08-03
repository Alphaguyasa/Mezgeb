# R8 rules for the release build.
#
# The guiding rule here is to add nothing that a library already ships as a consumer
# rule. Room, Hilt, WorkManager, DataStore and Compose all bundle their own, and a
# duplicate -keep in this file is not free: it is a claim about another library's
# internals that stops being true on the version that changes them, silently, while
# looking like diligence in a review.
#
# What is left is what this app does that R8 cannot see from the bytecode.

# --- kotlinx.serialization ---------------------------------------------------------
#
# The backup format is the one place in this app where a class is reconstructed from
# bytes rather than constructed by code. `BackupPayload` and every `*Entity` inside it
# are @Serializable, and their generated `serializer()` is reached through the
# `Companion` — a path R8 sees as unused and removes.
#
# kotlinx-serialization does ship these as consumer rules. They are repeated here
# because the failure they prevent is not a crash at startup: it is a restore that
# throws on a shop's only copy of their ledger, months after the build went out, on a
# file this app told them was safe to keep. That is worth six lines of redundancy.

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Enum values are serialized by name — Direction, Channel, ParseState and the rest.
# Renaming them writes a backup that only the build that wrote it can read.
-keepclassmembers enum et.alpha.mezgeb.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# --- Room ---------------------------------------------------------------------------
#
# Room's own consumer rules cover the generated implementations. What they do not cover
# is this project's refusal to use fallbackToDestructiveMigration: Room compares the
# live schema against the compiled one by identity hash at every open, and the entity
# field names are part of that comparison. Obfuscating a column name turns a normal
# launch into an IllegalStateException on a database that is perfectly intact.
-keepclassmembers class et.alpha.mezgeb.data.local.entity.** {
    <fields>;
}

# --- The capture services -----------------------------------------------------------
#
# Both are declared in the manifest, so AGP keeps and un-renames them automatically.
# They are named here anyway because they are the two classes whose disappearance is
# invisible: nothing crashes, the app opens normally, and payments simply stop being
# captured. A silent failure in the one feature the app exists for is worth being
# explicit about, even where the tooling already handles it.
-keep class et.alpha.mezgeb.capture.notification.MezgebNotificationListener { *; }
-keep class et.alpha.mezgeb.capture.boot.BootCompletedReceiver { *; }

# --- Tink, via androidx.security-crypto ---------------------------------------------
#
# Tink's classes carry Error Prone's @Immutable, which is a compile-time-only
# annotation: it is not on the runtime classpath and was never meant to be. R8 reports
# the dangling reference, and the reference is genuinely harmless.
#
# Scoped to the one annotation R8 actually named rather than the whole
# com.google.errorprone.** package. A broader -dontwarn would also silence the next
# missing class from that namespace, which might not be an annotation and might matter.
-dontwarn com.google.errorprone.annotations.Immutable

# --- Crash readability ---------------------------------------------------------------
#
# There is no crash reporter in this app and there never will be — it has no network
# permission. A stack trace is therefore something a person reads off a screen or out
# of logcat, once, with the phone in their hand, which only works if it names lines.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
