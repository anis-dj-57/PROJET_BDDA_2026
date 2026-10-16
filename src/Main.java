import java.util.Scanner;

/**
 * Point d'entrée de l'application console MiniSGBDR (issue du TP1 de l'année précédente nommée TP1_new).
 * Rôle : Maintenir une boucle de commandes interactive dans le terminal qui s'arrête lorsque l'utilisateur tape "EXIT".
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("   Bienvenue dans votre MiniSGBDR (BDDA)  ");
        System.out.println("   Tapez 'TEST' pour lancer les tests TP1 ");
        System.out.println("   Tapez 'EXIT' (en majuscules) pour quitter");
        System.out.println("==========================================");

        // Utilisation d'un try-with-resources pour fermer proprement le Scanner à la fin
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nSGBD> ");
                
                // Si le flux d'entrée est fermé, on quitte.
                // Ex : lancez le programme avec un script automatisé qui envoie des commandes à la chaîne,
                // une fois que le script a tout envoyé, le flux se ferme.
                if (!scanner.hasNextLine()) {
                    break;
                }

                String commande = scanner.nextLine().trim();

                // Condition d'arrêt stricte exigée par TP1_new : "EXIT" tout en majuscules
                if (commande.equals("EXIT")) {
                    System.out.println("Fermeture du MiniSGBDR. À bientôt !");
                    break;
                }

                // Commande pratique de débogage pour lancer les tests unitaires depuis la console
                if (commande.equalsIgnoreCase("TEST"))
                    DiskManagerTests.main(new String[0]);
                
            }
        }
    }
}