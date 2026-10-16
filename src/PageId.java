import java.util.Objects;

/**
 * Rôle : Représente l'identifiant (les coordonnées) d'une page sur le disque dur.
 * Elle associe un numéro de fichier (fileIdx) et un numéro d'une page (pageIdx) contenue dans ce fichier.
 */
public class PageId implements IPageId {

    // Indice du fichier sur le disque (par défaut 0 si on utilise un seul fichier data.bin)
    private final int fileIdx;

    // Indice (numéro) de la page/bloc à l'intérieur du fichier (0, 1, 2, ...)
    // Aussi : la page numéro pageIdx commence toujours exactement à l'octet offset = pageIdx * pageSize dans le fichier
    private final int pageIdx;

    
    public PageId(int fileIdx, int pageIdx) {
        this.fileIdx = fileIdx;
        this.pageIdx = pageIdx;
    }

    
    public PageId(int pageIdx) {
        this(0, pageIdx);
    }

    
    public int getFileIdx() {
        return this.fileIdx;
    }

    
    public int getPageIdx() {
        return this.pageIdx;
    }

    
    /**
     * Deux PageId sont égaux s'ils pointent vers le même fichier (fileIdx) et la même page (pageIdx).
     * 
     * @param obj L'objet à comparer avec l'instance courante.
     * @return true si les deux objets désignent la même page physique, false sinon.
     */
    @Override
    public boolean equals(Object obj) {
        // 1. Si ils ont exactement de la même référence mémoire, renvoyer true
        if (this == obj)
            return true;
        
        // 2. Si l'autre objet est null ou n'est pas de la classe PageId, renvoyer false
        if (obj == null || !(obj instanceof PageId) )
            return false;
        
        // 3. Transtypage de obj et comparaison des valeurs de fileIdx et pageIdx
        return ( this.fileIdx == ((PageId)obj).fileIdx ) && ( this.pageIdx == ((PageId)obj).pageIdx );
    }

    /**
     * Génère un code de hachage basé sur fileIdx et pageIdx.
     * Rôle : Afin de pouvoir stocker des PageId dans des collections comme HashSet ou HashMap dans le DiskManager.
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.fileIdx, this.pageIdx);
    }

    /**
     * Utile pour le débogage et les tests.
     * Note : on aurait pu utiliser une suite de concaténation de String
     * ce qui aurait été plus simple mais bien moins optimal pour la mémoire.
     */
    @Override
    public String toString() {
        return new StringBuilder("PageId(file=").append(this.fileIdx).append(", page=").append(this.pageIdx).append(")").toString();
    }
}