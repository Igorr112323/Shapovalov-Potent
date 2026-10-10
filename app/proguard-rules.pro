-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault

-keep class com.shapovalov.legalar.LegalReference { *; }
-keep class com.shapovalov.legalar.LegalDao { *; }
-keep class com.shapovalov.legalar.AppDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

-keep class com.google.mlkit.vision.text.latin.TextRecognizerOptions { *; }
-keep interface com.google.mlkit.vision.text.TextRecognizerOptionsInterface { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
