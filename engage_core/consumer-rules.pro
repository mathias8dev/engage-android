# Public models are serialized by name. Keep serializers when applications enable R8.
-keepattributes *Annotation*
-keep,includedescriptorclasses class io.engage.sdk.**$$serializer { *; }
