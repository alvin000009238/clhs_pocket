# ProGuard / R8 rules for SchoolGrades

# protobuf-lite reflects generated field names from its message metadata. Keep
# this small app-owned package intact because R8 can otherwise remove scalar
# fields while leaving their names in the metadata.
-keep class com.clhs.score.data.proto.** extends com.google.protobuf.GeneratedMessageLite {
  *;
}

# Biweekly loads biweekly.properties relative to this class name.
-keepnames class biweekly.Biweekly

# Biweekly creates unknown iCalendar parameter values and discovers predefined
# values through reflection.
-keepclassmembers class biweekly.parameter.** extends biweekly.parameter.EnumParameterValue {
  <init>(java.lang.String);
  <init>(java.lang.String, biweekly.ICalVersion[]);
  public static <fields>;
}

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt

# WorkManager stores worker class names in its database. Keep the app worker name
# stable without preventing R8 from shrinking WorkManager and its transitive deps.
-keepnames class com.clhs.score.reminders.GradeReminderWorker
-keepclassmembers class com.clhs.score.reminders.GradeReminderWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepnames class com.clhs.score.reminders.AnnouncementReminderWorker
-keepclassmembers class com.clhs.score.reminders.AnnouncementReminderWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
