# Definition of Done

Une issue est terminée lorsque :

- le code compile ;
- les tests concernés passent ;
- Lint n'introduit pas de régression non justifiée ;
- la compatibilité API 17 a été vérifiée ;
- les erreurs réseau sont gérées ;
- aucune donnée sensible n'est loggée ;
- la documentation est mise à jour si le comportement change ;
- le changement est testé sur GT-P5110 lorsqu'il touche UI, rendu, réseau ou performance ;
- les critères d'acceptation de l'issue sont satisfaits.

Une release est terminée lorsque, en plus :

- Ubuntu et Windows ont été testés ;
- l'APK release est reproductible ;
- le numéro de version et les notes de version sont présents ;
- un test de 2 h est passé pour V1 ;
- les checksums de l'APK sont publiés avec l'artefact.
