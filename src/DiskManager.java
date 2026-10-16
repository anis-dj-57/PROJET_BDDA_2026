import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Rôle : Gère la couche la plus basse du SGBD (Gestion espace disque).
 * S'occupe de 4 taches principales : allocation, désallocation, lecture et écriture des pages
 * dans un fichier binaire, ainsi que de la persistance des métadonnées entre deux exécutions.
 */
public class DiskManager implements IDiskManager {

    // Constantes définissant les noms des deux fichiers internes au dossier de travail dmDir
    private static final String DATA_FILE_NAME = "data.bin";
    private static final String META_FILE_NAME = "dm_meta.txt";

    // Indique si la méthode Init() a déjà été appelée avec succès sur cette instance
    private boolean isInitialized;

    // Référence vers le dossier de travail du DiskManager
    private File dmDirFile;

    // Référence vers le fichier binaire où sont stockées les pages
    private File dataFile;

    // Référence vers le fichier texte sauvegardant l'état du DiskManager (métadonnées)
    private File metaFile;

    // Taille d'une page en octets
    private int pageSize;

    // Indice de la prochaine page neuve à créer à la fin du fichier "data.bin"
    private int nextPageIdx;

    // Ensemble des pages actuellement allouées (actives) pour vérification rapide
    private Set<PageId> activePages;

    // File d'attente des pages désallouées prêtes à être réutilisées par AllocPage()
    private Queue<PageId> freePages;

    /**
     * Constructeur sans arguments (compléments TP1).
     * Initialise les structures de données internes en mémoire à vide.
     */
    public DiskManager() {
        this.isInitialized = false;
        this.dmDirFile = null;
        this.dataFile = null;
        this.metaFile = null;
        this.pageSize = 0;
        this.nextPageIdx = 0;
        this.activePages = new HashSet<>();
        this.freePages = new ArrayDeque<>();
    }

    /**
     * Initialise le gestionnaire disque sur un dossier cible avec une taille de page donnée.
     * Règle du complément TP1 : Ne doit accepter qu'un seul appel par instance !
     * 
     * @param dmDir    Chemin vers le dossier de travail du DiskManager.
     * @param pageSize Taille d'une page en octets (doit être strictement positive).
     * @throws DiskManagerException Si déjà initialisé, si arguments invalides, ou si données incompatibles.
     */
    @Override
    public void Init(String dmDir, int pageSize) throws DiskManagerException {
        // 1. Vérification de la contrainte d'appel unique à Init()
        if (this.isInitialized) {
            throw new DiskManagerException("Erreur : Init() a déjà été appelé sur ce DiskManager.");
        }

        // 2. Validation des arguments (chemin non vide et taille de page > 0)
        // Explication "dmDir.trim().length() == 0" : Vérifie si dmDir est totalement vide ou ne contient que des espaces. 
        // trim() : Elle supprime tous les espaces blancs.
        if (dmDir == null || dmDir.trim().length() == 0) {
            throw new DiskManagerException("Erreur : Le chemin du dossier dmDir est null ou vide.");
        }
        if (pageSize <= 0) {
            throw new DiskManagerException("Erreur : La taille de page doit être strictement positive (reçu : " + pageSize + ").");
        }

        File dir = new File(dmDir);

        // 3. Vérification et création éventuelle du dossier de travail
        if (dir.exists()) {
            // Si le chemin pointe vers un fichier ordinaire au lieu d'un répertoire, on lève une exception
            if (!dir.isDirectory()) {
                throw new DiskManagerException("Erreur : Le chemin spécifié existe mais n'est pas un répertoire : " + dmDir);
            }
        } else {
            // Si le dossier n'existe pas encore, on tente de le créer (avec ses dossiers parents)
            boolean created = dir.mkdirs();
            // Explication du choix d'un ET logique : Dans un contexte de concurrence, ce dérnier évite une exception
            // injustifiée si un autre thread en parallèle a créé le dossier une fraction de seconde avant, et évidement
            // dir.mkdirs() renverra false puisque l'instruction ne l'a pas créé elle-même.
            if (!created && !dir.exists()) {
                throw new DiskManagerException("Erreur : Impossible de créer le répertoire de travail : " + dmDir);
            }
        }

        // 4. Nettoyage préventif de toutes les données antérieures au DiskManager avant de s'attacher au nouveau dossier
        this.activePages.clear();
        this.freePages.clear();
        this.nextPageIdx = 0;
        this.dmDirFile = dir;
        // Les new File() d'au-dessous ne crée aucun fichier sur le disque, elles sert uniquement à créer une référence
        // en mémoire contenant le chemin d'accès vers les fichiers dataFile et metaFile.
        this.dataFile = new File(dir, DATA_FILE_NAME);
        this.metaFile = new File(dir, META_FILE_NAME);

        // 5. Inspection du contenu du dossier pour déterminer s'il est vide ou s'il contient déjà des données
        File[] existingFiles = dir.listFiles();
        if (existingFiles == null) {
            throw new DiskManagerException("Erreur : Impossible de lire le contenu du dossier : " + dmDir);
        }

        if (existingFiles.length == 0) {
            // CAS A : Le dossier est vide -> On initialise une nouvelle base vierge
            this.pageSize = pageSize;
            try {
                // Contrairement à new File, createNewFile() crée physiquement un nouveau fichier vide sur le disque dur
                this.dataFile.createNewFile();
            } catch (IOException e) {
                throw new DiskManagerException("Erreur lors de la création du fichier de données initial.", e);
            }
            this.isInitialized = true;
            // On sauvegarde immédiatement les métadonnées initiales (notamment pageSize) sur le disque
            this.Save();
        } else {
            // CAS B : Le dossier contient déjà des fichiers -> On vérifie la compatibilité et on recharge l'état
            if (!this.metaFile.exists() || !this.dataFile.exists()) {
                throw new DiskManagerException("Erreur : Le dossier n'est pas vide mais ne contient pas de données DiskManager valides.");
            }
            // Chargement des métadonnées et vérification que le pageSize demandé correspond à celui sauvegardé
            loadMetadataAndCheckCompatibility(pageSize);
            this.isInitialized = true;
        }
    }

    /**
     * Méthode privée utilitaire qui lit le fichier dm_meta.txt pour restaurer l'état du DiskManager
     * et vérifie que la taille de page existante est compatible avec celle passée en argument via la méthode Init().
     */
    private void loadMetadataAndCheckCompatibility(int pageSize) throws DiskManagerException {
        // Utilisation du try-with-resources pour fermer automatiquement le BufferedReader
        try (BufferedReader br = new BufferedReader(new FileReader(this.metaFile))) {
            String line;
            Integer savedPageSize = null;
            Integer savedNextPageIdx = null;

            // 1. Restauration de l'état du DiskManager a partir du fichier de métadonnées
            // Lecture ligne par ligne du fichier de métadonnées
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(":", 2);
                if (parts.length < 2) {
                    throw new DiskManagerException("Erreur : Fichier de métadonnées corrompu (ligne mal formée : " + line + ").");
                }

                String key = parts[0].trim();
                String value = parts[1].trim();

                // Analyse de chaque clé stockée dans dm_meta.txt
                if (key.equals("pageSize")) {
                    savedPageSize = Integer.parseInt(value);
                } else if (key.equals("nextPageIdx")) {
                    savedNextPageIdx = Integer.parseInt(value);
                } else if (key.equals("activePage")) {
                    int pIdx = Integer.parseInt(value);
                    this.activePages.add(new PageId(0, pIdx));
                } else if (key.equals("freePage")) {
                    int pIdx = Integer.parseInt(value);
                    this.freePages.add(new PageId(0, pIdx));
                }
            }


            // 2. Vérification de la compatibilité entre les métadonnées autérieurs et les attributs déjà déterminer du DiskManager
            // Vérification que les informations vitales ont bien été trouvées dans le fichier
            if (savedPageSize == null || savedNextPageIdx == null) {
                throw new DiskManagerException("Erreur : Fichier de métadonnées incomplet.");
            }

            // Vérification de la compatibilité de la taille de page
            if (savedPageSize != pageSize) {
                throw new DiskManagerException("Erreur : Taille de page incompatible ! Données existantes en "
                        + savedPageSize + " octets, mais Init demandé avec " + pageSize + " octets.");
            }

            // Vérification que la taille du fichier data.bin est cohérente avec les pages
            long expectedLength = (long) savedNextPageIdx * savedPageSize;
            if (this.dataFile.length() < expectedLength) {
                throw new DiskManagerException("Erreur : Le fichier data.bin est tronqué ou corrompu.");
            }

            this.pageSize = savedPageSize;
            this.nextPageIdx = savedNextPageIdx;

        } catch (NumberFormatException e) {
            throw new DiskManagerException("Erreur : Format numérique invalide dans le fichier de métadonnées.", e);
        } catch (IOException e) {
            throw new DiskManagerException("Erreur d'entrée/sortie lors du chargement des métadonnées.", e);
        }
    }

    /**
     * Sauvegarde l'état courant du DiskManager (taille de page, compteur, pages actives et libres)
     * dans le fichier dm_meta.txt du dossier de travail.
     * 
     * @throws DiskManagerException Si le DiskManager n'est pas initialisé ou en cas d'erreur disque.
     */
    @Override
    public void Save() throws DiskManagerException {
        checkInitialized();

        // Écriture bufferisée avec fermeture automatique du flux via try-with-resources
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(this.metaFile)))) {
            // 1. Sauvegarde des paramètres globaux
            pw.println("pageSize:" + this.pageSize);
            pw.println("nextPageIdx:" + this.nextPageIdx);

            // 2. Sauvegarde de la liste des pages actuellement actives
            for (PageId pid : this.activePages) {
                pw.println("activePage:" + pid.getPageIdx());
            }

            // 3. Sauvegarde de la file des pages désallouées (disponibles pour réutilisation)
            for (PageId pid : this.freePages) {
                pw.println("freePage:" + pid.getPageIdx());
            }
        } catch (IOException e) {
            throw new DiskManagerException("Erreur lors de la sauvegarde des métadonnées dans Save().", e);
        }
    }

    /**
     * Alloue une page à la demande d'une couche supérieure.
     * Algorithme :
     * 1. Si une page désallouée est disponible dans freePages, on la réutilise.
     * 2. Sinon, on ajoute une nouvelle page à la fin du fichier data.bin.
     * 
     * @return L'identifiant IPageId de la page allouée.
     * @throws DiskManagerException Si non initialisé ou si l'écriture sur le disque échoue.
     */
    @Override
    public IPageId AllocPage() throws DiskManagerException {
        checkInitialized();

        PageId allocatedPage;

        // CAS 1 : Il existe au moins une page précédemment désallouée -> on la recycle
        if (!this.freePages.isEmpty()) {
            allocatedPage = this.freePages.poll();
        } else {
            // CAS 2 : Aucune page libre -> on crée une nouvelle page à la fin du fichier data.bin
            int newPageIdx = this.nextPageIdx;
            this.nextPageIdx++;
            allocatedPage = new PageId(0, newPageIdx);

            // On réserve physiquement l'espace de cette nouvelle page dans data.bin (rempli de 0)
            try (RandomAccessFile raf = new RandomAccessFile(this.dataFile, "rw")) {
                long offset = (long) newPageIdx * this.pageSize;
                raf.seek(offset);
                byte[] emptyPage = new byte[this.pageSize];
                raf.write(emptyPage);
            } catch (IOException e) {
                // En cas d'échec disque, on annule l'incrémentation du compteur
                this.nextPageIdx--;
                throw new DiskManagerException("Erreur d'écriture disque lors de l'allocation d'une nouvelle page.", e);
            }
        }

        // On enregistre la page comme étant désormais active (en cours d'utilisation)
        this.activePages.add(allocatedPage);
        return allocatedPage;
    }

    /**
     * Lit le contenu disque de la page identifiée par ipid et le copie dans le ByteBuffer fourni.
     * 
     * @param ipid   L'identifiant de la page à lire.
     * @param buffer Le tampon mémoire (alloué par l'appelant) à remplir.
     * @throws DiskManagerException Si la page est invalide/inactive, le buffer trop petit ou erreur I/O.
     */
    @Override
    public void ReadPage(IPageId ipid, ByteBuffer buffer) throws DiskManagerException {
        checkInitialized();

        // 1. Validation de l'identifiant de page (doit être un PageId valide et actuellement alloué)
        PageId pid = validateActivePageId(ipid);

        // 2. Validation du ByteBuffer fourni par l'appelant
        if (buffer == null) {
            throw new DiskManagerException("Erreur ReadPage : Le ByteBuffer fourni est null.");
        }
        if (buffer.capacity() < this.pageSize) {
            throw new DiskManagerException("Erreur ReadPage : La capacité du ByteBuffer (" + buffer.capacity()
                    + ") est inférieure à la taille d'une page (" + this.pageSize + ").");
        }

        // 3. Lecture dans le fichier binaire au bon offset avec RandomAccessFile
        long offset = (long) pid.getPageIdx() * this.pageSize;
        byte[] pageData = new byte[this.pageSize];

        try (RandomAccessFile raf = new RandomAccessFile(this.dataFile, "r")) {
            raf.seek(offset);
            // readFully garantit qu'on lit exactement pageSize octets
            raf.readFully(pageData);
        } catch (IOException e) {
            throw new DiskManagerException("Erreur lors de la lecture disque de la page " + pid, e);
        }

        // 4. Copie des octets lus dans le ByteBuffer de l'appelant
        buffer.clear();
        buffer.put(pageData);
        // Ramène uniquement le curseur à 0 (Préparation à la lecture)
        buffer.rewind();
    }

    /**
     * Écrit le contenu du ByteBuffer fourni sur le disque dans la page identifiée par ipid.
     * 
     * @param ipid   L'identifiant de la page cible.
     * @param buffer Le tampon mémoire contenant les octets à écrire.
     * @throws DiskManagerException Si la page est invalide/inactive, le buffer trop petit ou erreur I/O.
     */
    @Override
    public void WritePage(IPageId ipid, ByteBuffer buffer) throws DiskManagerException {
        checkInitialized();

        // 1. Validation de l'identifiant de page (doit être un PageId valide et actuellement alloué)
        PageId pid = validateActivePageId(ipid);

        // 2. Validation du ByteBuffer fourni par l'appelant
        if (buffer == null) {
            throw new DiskManagerException("Erreur WritePage : Le ByteBuffer fourni est null.");
        }
        if (buffer.capacity() < this.pageSize) {
            throw new DiskManagerException("Erreur WritePage : La capacité du ByteBuffer (" + buffer.capacity()
                    + ") est inférieure à la taille d'une page (" + this.pageSize + ").");
        }

        // 3. Extraction des pageSize octets depuis le début du ByteBuffer
        byte[] pageData = new byte[this.pageSize];
        buffer.rewind();
        buffer.get(pageData);
        buffer.rewind();

        // 4. Écriture sur le disque à l'offset exact correspondant au numéro de page
        long offset = (long) pid.getPageIdx() * this.pageSize;
        try (RandomAccessFile raf = new RandomAccessFile(this.dataFile, "rw")) {
            raf.seek(offset);
            raf.write(pageData);
        } catch (IOException e) {
            throw new DiskManagerException("Erreur lors de l'écriture disque sur la page " + pid, e);
        }
    }

    /**
     * Désalloue la page identifiée par ipid et la marque comme disponible pour un futur AllocPage().
     * 
     * @param ipid L'identifiant de la page à libérer.
     * @throws DiskManagerException Si la page n'est pas actuellement allouée (ou déjà désallouée).
     */
    @Override
    public void DeallocPage(IPageId ipid) throws DiskManagerException {
        checkInitialized();

        // Vérifie que la page existe et qu'elle fait bien partie des pages actives
        PageId pid = validateActivePageId(ipid);

        // On la retire des pages actives et on l'ajoute à la file des pages libres réutilisables
        this.activePages.remove(pid);
        this.freePages.add(pid);
    }

    /**
     * Retourne le nombre total de pages actuellement allouées (actives et non désallouées).
     * 
     * @return Nombre de pages actives (0 si non initialisé).
     */
    @Override
    public int GetActivePageCount() {
        if (!this.isInitialized) {
            return 0;
        }
        return this.activePages.size();
    }

    /**
     * Retourne la taille d'une page en octets configurée dans ce DiskManager.
     * 
     * @return La taille de page en octets.
     */
    @Override
    public int GetPageSize() {
        return this.pageSize;
    }

    /**
     * Méthode utilitaire interne vérifiant que Init() a bien été appelé avant toute opération.
     */
    private void checkInitialized() throws DiskManagerException {
        if (!this.isInitialized) {
            throw new DiskManagerException("Erreur : Le DiskManager n'a pas encore été initialisé avec Init().");
        }
    }

    /**
     * Méthode utilitaire interne vérifiant qu'un IPageId est non null, du bon type,
     * et qu'il correspond bien à une page actuellement active (allouée).
     */
    private PageId validateActivePageId(IPageId ipid) throws DiskManagerException {
        if (ipid == null) {
            throw new DiskManagerException("Erreur : L'identifiant de page (IPageId) est null.");
        }
        if (!(ipid instanceof PageId)) {
            throw new DiskManagerException("Erreur : Type d'identifiant de page inconnu.");
        }
        PageId pid = (PageId) ipid;
        if (!this.activePages.contains(pid)) {
            throw new DiskManagerException("Erreur : La page " + pid + " n'est pas allouée ou a déjà été désallouée.");
        }
        return pid;
    }
}