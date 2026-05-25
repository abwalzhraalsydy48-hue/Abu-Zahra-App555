# ============================================================================
# Ultimate Recovery Pro - ProGuard Rules
# ============================================================================

# ============================================================================
# Standard Android Rules
# ============================================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Android core
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends android.view.View
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends android.app.DialogFragment

# AndroidX / Jetpack
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# Support library
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# View constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# onClick handlers
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Parcelable implementation (Kotlin)
-keepclassmembers class * {
    ** Companion;
    ** CREATOR;
}

# R classes
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# Room Database
# ============================================================================
# Keep all Room entities
-keep class * extends androidx.room.Entity
-keep @androidx.room.Entity class *
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Database class *

# Keep all DAOs
-keep class * extends androidx.room.Dao
-keep @androidx.room.Dao class *
-keep @androidx.room.Dao interface *

# Keep Room type converters
-keep class * extends androidx.room.TypeConverter
-keep @androidx.room.TypeConverters class *

# Keep all Entity fields (Room uses reflection)
-keepclassmembers class * {
    @androidx.room.ColumnInfo <fields>;
    @androidx.room.PrimaryKey <fields>;
    @androidx.room.Embedded <fields>;
    @androidx.room.Ignore <fields>;
    @androidx.room.Relation <fields>;
}

# Keep Entity constructor
-keepclasseswithmembers class * {
    @androidx.room.Entity <init>(...);
}

# Keep Embedded classes
-keep class * {
    @androidx.room.Embedded *;
}

# Keep all Room generated classes
-keep class * extends androidx.room.paging.LimitOffsetDataSource { *; }
-keep class * extends androidx.room.util.DBUtil { *; }

# Keep TypeConverters in the app
-keep class com.ultimaterecovery.pro.data.local.converter.** { *; }

# ============================================================================
# Kotlin Coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Allow renaming of coroutines internal classes
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }

# Flow
-keep class kotlinx.coroutines.flow.** { *; }
-keepnames class kotlinx.coroutines.flow.internal.AbstractSharedFlow { *; }
-keepnames class kotlinx.coroutines.flow.internal.AbstractSharedFlowSlot { *; }

# ============================================================================
# Hilt / Dagger
# ============================================================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep classes annotated with Hilt annotations
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.EntryPoint class * { *; }

# Keep Hilt components
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class **_HiltModules* { *; }
-keep class **_HiltComponents* { *; }
-keep class **_GeneratedInjector { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }

# Keep Application class for Hilt
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Suppress warnings
-dontwarn dagger.hilt.**
-dontwarn javax.inject.**

# ============================================================================
# Gson
# ============================================================================
# Keep data classes used with Gson
-keepattributes Signature
-keepattributes *Annotation*

# Gson specific rules
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
-dontwarn sun.misc.**

# Keep classes with Gson annotations
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep generic signature of TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Application data classes for Gson serialization
-keep class com.ultimaterecovery.pro.data.model.** { <fields>; }
-keep class com.ultimaterecovery.pro.data.local.entity.** { <fields>; }

# Keep all classes with @SerializedName
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================================================
# Model / Entity Classes - Keep all fields
# ============================================================================
-keep class com.ultimaterecovery.pro.data.local.entity.** { *; }
-keep class com.ultimaterecovery.pro.data.model.** { *; }
-keep class com.ultimaterecovery.pro.engine.recovery.RecoveryResult { *; }
-keep class com.ultimaterecovery.pro.engine.recovery.RecoveryResult$* { *; }

# ============================================================================
# libsu (topjohnwu/libsu)
# ============================================================================
-keep class com.topjohnwu.superuser.** { *; }
-keep class com.topjohnwu.superuser.internal.** { *; }
-keep interface com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Keep libsu IPC classes
-keep class * extends com.topjohnwu.superuser.internal.IRootIPC { *; }
-keepclassmembers class com.topjohnwu.superuser.internal.Utils { *; }

# ============================================================================
# Glide
# ============================================================================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.bumptech.glide.** { *; }
-keep interface com.bumptech.glide.** { *; }

# Glide generated API
-keep class com.ultimaterecovery.pro.GlideApp { *; }
-keep class com.ultimaterecovery.pro.GlideRequest { *; }
-keep @com.bumptech.glide.annotation.GlideExtension class * { *; }
-keep @com.bumptech.glide.annotation.GlideOption class * { *; }
-keep @com.bumptech.glide.annotation.GlideType class * { *; }

# Glide model loaders
-keep class * extends com.bumptech.glide.load.model.ModelLoader { *; }
-keep class * implements com.bumptech.glide.load.model.ModelLoaderFactory { *; }

-dontwarn com.bumptech.glide.**

# ============================================================================
# ExoPlayer
# ============================================================================
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ExoPlayer core
-keep class com.google.android.exoplayer2.ExoPlayer { *; }
-keep class com.google.android.exoplayer2.SimpleExoPlayer { *; }
-keep class com.google.android.exoplayer2.Player { *; }

# ExoPlayer extractors
-keep class * extends com.google.android.exoplayer2.extractor.Extractor { *; }
-keep class * implements com.google.android.exoplayer2.source.MediaSourceFactory { *; }

# ExoPlayer UI
-keep class com.google.android.exoplayer2.ui.** { *; }

# Media3 (if using newer version)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ============================================================================
# Lottie
# ============================================================================
-keep class com.airbnb.lottie.** { *; }
-keep interface com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**

# Lottie composition factory uses reflection
-keepclassmembers class com.airbnb.lottie.LottieAnimationView {
    <init>(...);
}

# ============================================================================
# Serializable / Parcelable Classes
# ============================================================================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep all Parcelable implementations in the app package
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ============================================================================
# App-Specific Reflection Rules
# ============================================================================
# Keep all classes used by reflection in the app package
-keep class com.ultimaterecovery.pro.data.local.database.UltimateRecoveryDatabase { *; }
-keep class com.ultimaterecovery.pro.UltimateRecoveryApplication { *; }

# Keep ViewModel classes (used by reflection in Fragment/Activity delegates)
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keep class com.ultimaterecovery.pro.ui.viewmodel.** { *; }

# Keep all Fragment and Activity classes (used by navigation/reflection)
-keep class com.ultimaterecovery.pro.ui.activities.** { *; }
-keep class com.ultimaterecovery.pro.ui.fragments.** { *; }
-keep class com.ultimaterecovery.pro.service.** { *; }

# Keep Repository classes (injected by Hilt)
-keep class com.ultimaterecovery.pro.data.repository.** { *; }

# Keep Manager classes
-keep class com.ultimaterecovery.pro.manager.** { *; }
-keep class com.ultimaterecovery.pro.utils.** { *; }
-keep class com.ultimaterecovery.pro.engine.** { *; }

# ============================================================================
# Kotlin Specific
# ============================================================================
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin coroutines (additional)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin reflection
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# Kotlin Unit
-keep class kotlin.Unit
-keep class kotlin.jvm.internal.** { *; }

# Kotlin companion objects
-keepclassmembers class * {
    ** Companion;
}

# ============================================================================
# Retrofit (if used)
# ============================================================================
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-dontwarn org.codehaus.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ============================================================================
# Jackson (if used)
# ============================================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }

# ============================================================================
# Miscellaneous
# ============================================================================
# Keep source file names & line numbers for crash reports
-keepattributes SourceFile,LineNumberTable

# Remove all logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Warning suppression
-dontwarn javax.annotation.**
-dontwarn org.codehaus.**
-dontwarn java.lang.invoke.StringConcatFactory
