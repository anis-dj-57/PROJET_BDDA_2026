import java.io.File;
import java.nio.ByteBuffer;

/**
 * Contient l'ensemble des tests unitaires du gestionnaire disque.
 * Rôle : Vérifier de manière automatisée le bon fonctionnement de PageId et DiskManager
 * (allocation/désallocation, lecture/écriture, persistance) ainsi que la gestion des erreurs
 * via DiskManagerException.
 */
public class DiskManagerTests {

	
    public static void main(String[] args) {
        System.out.println("====================================================");
        System.out.println("   DÉMARRAGE DES TESTS DU DISKMANAGER (TP1)         ");
        System.out.println("====================================================\n");

        
        testEcritureEtLecturePage();
        testAllocationEtReutilisation();
        testPersistanceSaveEtReInit();
        testCasErreursExceptions();

        System.out.println("\n====================================================");
        System.out.println("   FIN DES TESTS DU DISKMANAGER                     ");
        System.out.println("====================================================");
    }

    /**
     * TEST 1 : Vérifie l'allocation d'une page, l'écriture d'octets puis leur relecture.
     * Utilise une taille de page réduite à 4 octets comme suggéré dans l'énoncé du TP1
     * pour faciliter l'affichage et la vérification du contenu.
     */
    public static void testEcritureEtLecturePage() {
        System.out.println("-> Exécution de testEcritureEtLecturePage (pageSize = 4 octets)...");
        
        // Préparation d'un dossier de travail
        File dir = new File("./test_dm_rw");
        nettoyerDossier(dir);

        try {
            // 1. Initialisation du DiskManager
            DiskManager dm = new DiskManager();
            dm.Init(dir.getAbsolutePath(), 4);

            // 2. Demande d'allocation d'une nouvelle page sur le disque
            IPageId pid = dm.AllocPage();

            // 3. Création et remplissage d'un ByteBuffer de 4 octets par l'appelant
            ByteBuffer writeBuf = ByteBuffer.allocate(4);
            writeBuf.put(new byte[]{10, 20, 30, 40});

            // 4. Écriture du contenu du buffer dans la page allouée sur le disque
            dm.WritePage(pid, writeBuf);

            // 5. Création d'un second ByteBuffer vierge pour accueillir les données lues
            ByteBuffer readBuf = ByteBuffer.allocate(4);
            dm.ReadPage(pid, readBuf);

            // 6. Vérification que chaque octet relu correspond exactement à l'octet écrit
            if (readBuf.get(0) == 10 && readBuf.get(1) == 20 && readBuf.get(2) == 30 && readBuf.get(3) == 40) {
                System.out.println("   [OK] testEcritureEtLecturePage réussi ! Octets relus : ["
                        + readBuf.get(0) + ", " + readBuf.get(1) + ", " + readBuf.get(2) + ", " + readBuf.get(3) + "]");
            } else {
                System.err.println("   [ÉCHEC] Les octets relus ne correspondent pas aux octets écrits !");
            }

            // 7. Sauvegarde de l'état du DiskManager
            dm.Save();
            
        } catch (Exception e) {
            System.err.println("   [ÉCHEC] Exception inattendue dans testEcritureEtLecturePage : " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Nettoyage systématique du dossier de test après exécution
            nettoyerDossier(dir);
        }
    }

    /**
     * TEST 2 : Vérifie l'algorithme d'allocation et de désallocation.
     * S'assure qu'une page libérée via DeallocPage() est immédiatement réutilisée
     * lors du prochain appel à AllocPage(), et que GetActivePageCount() reste exact.
     */
    public static void testAllocationEtReutilisation() {
        System.out.println("-> Exécution de testAllocationEtReutilisation...");
        File dir = new File("./test_dm_alloc");
        nettoyerDossier(dir);

        try {
            // 1. Initialisation du DiskManager
            DiskManager dm = new DiskManager();
            dm.Init(dir.getAbsolutePath(), 4);

            // 2. Allocation de deux pages distinctes
            IPageId p0 = dm.AllocPage();
            IPageId p1 = dm.AllocPage();

            // Vérification que le compteur de pages actives indique bien 2
            if (dm.GetActivePageCount() != 2) {
                System.err.println("   [ÉCHEC] GetActivePageCount devrait valoir 2, mais vaut " + dm.GetActivePageCount());
                return;
            }

            // 3. Désallocation de la première page (p0)
            dm.DeallocPage(p0);
            
            // Vérification que le compteur de pages actives est descendu à 1
            if (dm.GetActivePageCount() != 1) {
                System.err.println("   [ÉCHEC] Après DeallocPage, GetActivePageCount devrait valoir 1.");
                return;
            }

            // 4. Nouvelle allocation : le DiskManager doit obligatoirement recycler p0 !
            IPageId pReutilisee = dm.AllocPage();
            
            // Utilisation de la méthode equals() de IPageId pour vérifier que c'est bien la même page
            if (p0.equals(pReutilisee) && dm.GetActivePageCount() == 2) {
                System.out.println("   [OK] testAllocationEtReutilisation réussi ! La page désallouée " + p0 + " a bien été recyclée.");
            } else {
                System.err.println("   [ÉCHEC] La page désallouée n'a pas été réutilisée (obtenu : " + pReutilisee + ").");
            }

        } catch (Exception e) {
            System.err.println("   [ÉCHEC] Exception inattendue dans testAllocationEtReutilisation : " + e.getMessage());
            e.printStackTrace();
        } finally {
            nettoyerDossier(dir);
        }
    }

    /**
     * TEST 3 : Vérifie la persistance des données et des métadonnées sur le disque.
     * Simule deux exécutions successives du SGBD sur le même dossier de travail.
     */
    public static void testPersistanceSaveEtReInit() {
        System.out.println("-> Exécution de testPersistanceSaveEtReInit...");
        File dir = new File("./test_dm_persist");
        nettoyerDossier(dir);

        try {
            // --- PREMIÈRE EXÉCUTION (Création, écriture et sauvegarde) ---
            DiskManager dm1 = new DiskManager();
            dm1.Init(dir.getAbsolutePath(), 4);
            
            // On alloue deux pages : p0 et p1
            IPageId p0 = dm1.AllocPage();
            IPageId p1 = dm1.AllocPage();

            // On écrit l'entier 42 (qui occupe 4 octets) dans la page p1
            ByteBuffer bufEcriture = ByteBuffer.allocate(4);
            bufEcriture.putInt(42);
            dm1.WritePage(p1, bufEcriture);

            // On désalloue p0 (seule p1 reste active) et on sauvegarde l'état sur le disque
            dm1.DeallocPage(p0);
            dm1.Save();

            // --- DEUXIÈME EXÉCUTION (Rechargement depuis le même dossier) ---
            DiskManager dm2 = new DiskManager();
            dm2.Init(dir.getAbsolutePath(), 4);

            // 1. Vérification que dm2 a bien rechargé le bon nombre de pages actives (1 seule : p1)
            if (dm2.GetActivePageCount() != 1 || dm2.GetPageSize() != 4) {
                System.err.println("   [ÉCHEC] Persistance : Métadonnées rechargées incorrectes.");
                return;
            }

            // 2. Vérification que la lecture de p1 avec dm2 restitue bien l'entier 42
            ByteBuffer bufLecture = ByteBuffer.allocate(4);
            dm2.ReadPage(p1, bufLecture);
            int valeurRelue = bufLecture.getInt();

            // 3. Vérification que la page p0 (désallouée à l'exécution 1) a bien été sauvegardée comme libre
            IPageId pRecyclee = dm2.AllocPage();

            if (valeurRelue == 42 && p0.equals(pRecyclee)) {
                System.out.println("   [OK] testPersistanceSaveEtReInit réussi ! Données (42) et pages libres restaurées.");
            } else {
                System.err.println("   [ÉCHEC] Restauration incorrecte (valeur relue = " + valeurRelue + ", page recyclée = " + pRecyclee + ").");
            }

        } catch (Exception e) {
            System.err.println("   [ÉCHEC] Exception inattendue dans testPersistanceSaveEtReInit : " + e.getMessage());
            e.printStackTrace();
        } finally {
            nettoyerDossier(dir);
        }
    }

    /**
     * TEST 4 : Vérifie que DiskManagerException est correctement levée dans les cas d'erreur
     * (double appel à Init, taille de page incompatible, lecture/désallocation d'une page inactive).
     */
    public static void testCasErreursExceptions() {
        System.out.println("-> Exécution de testCasErreursExceptions...");
        File dir = new File("./test_dm_errors");
        nettoyerDossier(dir);

        try {
            DiskManager dm = new DiskManager();
            dm.Init(dir.getAbsolutePath(), 4);

            // 1. Test de la règle du Complément TP1 : interdiction d'appeler Init() deux fois
            boolean erreurDoubleInitCapturee = false;
            try {
                dm.Init(dir.getAbsolutePath(), 4);
            } catch (DiskManagerException e) {
                // La levée de DiskManagerException est le comportement attendu !
                erreurDoubleInitCapturee = true;
            }
            if (!erreurDoubleInitCapturee) {
                System.err.println("   [ÉCHEC] Un deuxième appel à Init() aurait dû lever DiskManagerException !");
                return;
            }

            // 2. Test d'incompatibilité de pageSize lors de la réutilisation d'un dossier existant
            dm.Save();
            DiskManager dmIncompatible = new DiskManager();
            boolean erreurPageSizeIncompatible = false;
            try {
                // Le dossier a été initialisé avec pageSize = 4, on tente de l'ouvrir avec pageSize = 8
                dmIncompatible.Init(dir.getAbsolutePath(), 8);
            } catch (DiskManagerException e) {
                erreurPageSizeIncompatible = true;
            }
            if (!erreurPageSizeIncompatible) {
                System.err.println("   [ÉCHEC] Init avec un pageSize incompatible (8 au lieu de 4) aurait dû lever DiskManagerException !");
                return;
            }

            // 3. Test de désallocation et de lecture sur une page déjà désallouée
            IPageId pid = dm.AllocPage();
            dm.DeallocPage(pid);
            
            boolean erreurDoubleDealloc = false;
            try {
                dm.DeallocPage(pid);
            } catch (DiskManagerException e) {
                erreurDoubleDealloc = true;
            }

            boolean erreurLecturePageDesallouee = false;
            try {
                ByteBuffer buf = ByteBuffer.allocate(4);
                dm.ReadPage(pid, buf);
            } catch (DiskManagerException e) {
                erreurLecturePageDesallouee = true;
            }

            if (!erreurDoubleDealloc || !erreurLecturePageDesallouee) {
                System.err.println("   [ÉCHEC] Utiliser une page déjà désallouée aurait dû lever DiskManagerException !");
                return;
            }

            System.out.println("   [OK] testCasErreursExceptions réussi ! Toutes les DiskManagerException attendues ont été levées.");

        } catch (Exception e) {
            System.err.println("   [ÉCHEC] Exception non prévue dans testCasErreursExceptions : " + e.getMessage());
            e.printStackTrace();
        } finally {
            nettoyerDossier(dir);
        }
    }

    /**
     * Méthode utilitaire privée permettant de vider et supprimer un répertoire de test
     * afin de garantir que chaque test démarre dans un environnement propre.
     * 
     * @param dir Le dossier à supprimer.
     */
    private static void nettoyerDossier(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            // delete() supprime le dossier physique sur le disque, mais la variable dir existe toujours dans le
            // programme et connaît toujours son chemin
            dir.delete();
        }
    }
}