-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault

-keep class com.shapovalov.legalar.LegalReference { *; }
-keep class com.shapovalov.legalar.LegalDao { *; }
-keep class com.shapovalov.legalar.AppDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**
-dontwarn com.googlecode.leptonica.android.**

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
