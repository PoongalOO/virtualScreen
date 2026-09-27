# SS-001 : minifyEnabled=false pour ce squelette, fichier conservé pour les
# règles futures (SS-093 — génération de l'APK release).
#
# SS-093 (2026-09-28) : minification toujours désactivée pour la release 0.2.0, décision explicite. Aucune règle
# n'a été écrite ni vérifiée sur la GT-P5110 (custom Views instanciées par nom depuis les layouts — RemoteSurfaceView,
# KeyboardInputView — et code qui pourrait dépendre de la réflexion) ; l'activer sans ces règles risquerait de casser
# l'application au lancement sans que les tests JVM ne le voient. À réviser si la minification est activée un jour :
# au minimum garder les constructeurs de vue à un seul argument `Context`/deux arguments `Context, AttributeSet`
# (`-keepclasseswithmembers class * extends android.view.View { public <init>(android.content.Context); ... }`),
# et retester intégralement sur l'appareil avant de publier.
