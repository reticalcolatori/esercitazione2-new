package com;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;


//Risassuntino per la presentazione:
//1) Controllo argomenti
//2) Istanzio oggetto client
//3) REPL in cui chiedo all'utente il direttorio da copiare.
//   Durante il REPL viene gestita una connessione TCP.

public class MultiplePutClient {

    private static final int SOCKET_CONN_ERR = 1; // Connessione non andata a buon fine.
    private static final int IO_ERR = 3; // Errore IO
    private static final int ARGS_ERR = 4; // Errore negli argomenti

    // Invocazione client:
    // MultiplePutClient serverAddress serverPort dimSoglia

	// Il client trasferisce solo se il file supera una dimensione minima.
	// Protocollo richiesta put file:
	// 1) invio nomefile

	// 2) In ricezione ho una conferma: se positiva posso trasferire il file.
	// L'esito è positivo solo se nel direttorio corrente del server non è presente
	// un file con nome uguale.
	// Il server rimane sullo stesso direttorio.

	// 3)Invio la lunghezza
	// lunghezza(long)

	// 4)Il server invia un ACK

	// 5)Quando ho finito di inviare tutti i file (anche 0) faccio la shutdown di
	// output.
	// 6)Il server invierà una conferma.

    // Risposte dal server
    private static final String RESULT_ATTIVA = "attiva";
    //private static final String RESULT_SALTA_FILE = "salta file"; --> concettualmente giusta così in pratica never used!
    //nonostante il server la usi! (è inclusa nel ramo else)
    private static final String RESULT_OK = "OK";

    private static boolean isPortValid(int port) {
        return 1024 < port && port < 0x10000;
    }

    public static void main(String[] args) {
        // MultiplePutClient serverAddress serverPort dimSoglia

        if (args.length != 3) {
            System.err.println("usage java MultiplePutClient serverAddress serverPort dimSoglia");
            System.exit(ARGS_ERR);
        }

        int dimensioneSoglia = 0;

        InetAddress serverAddress = null;
        int serverPort = 0;

        try {
            serverAddress = InetAddress.getByName(args[0]);
            serverPort = Integer.parseInt(args[1]);
            dimensioneSoglia = Integer.parseInt(args[2]);

        } catch (IllegalArgumentException | IOException e) {
            e.printStackTrace();
            System.exit(ARGS_ERR);
        }

        //Checks
        if (!isPortValid(serverPort)){
            System.err.println(("server port non valida"));
            System.exit(ARGS_ERR);
        }

        if (dimensioneSoglia < 0){
            System.err.println(("dimensioneSoglia non valida (<0)"));
            System.exit(ARGS_ERR);
        }

        //Posso avviare il client
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String dirname = "";
        try {
        	System.out.println("Inserisci il nome della directory da trasferire sul server (EOF per terminare):");
            while ((dirname = in.readLine()) != null) {
                // Apro la directory e e recupero un array dei files
                File dirFile = new File(dirname);

                // Controllo directory.
                if (!dirFile.isDirectory()) {
                    System.out.println("Directory non valida");
                    return;
                }
                //è una directory lo comunico all'utente
                System.out.println("OK! Procedo ad analizzare e caricare sul server il contenuto della directory: " + dirFile.getName());

                // Estraggo i file contenuti nella directory
                File[] files = dirFile.listFiles();

                // Directory vuota non devo fare nulla.
                if (files.length == 0) {
                    System.out.println("Directory vuota, non eseguo trasferimenti.");
                    return;
                }

                //preparo strutture input/output
                Socket socket = null;

                DataInputStream socketDataIn = null;
                DataOutputStream socketDataOut = null;

                String risposta = null;

                // Apro la connessione
                try {
                    socket = new Socket(serverAddress, serverPort);
                    socketDataIn = new DataInputStream(socket.getInputStream());
                    socketDataOut = new DataOutputStream(socket.getOutputStream());
                } catch (IOException e) {
                    // se ho IOExeption devo terminare
                    e.printStackTrace();
                    System.exit(SOCKET_CONN_ERR);
                }

                for (File file : files) {
                    // Per ogni file verifico se la dimensione supera la soglia minima
                    long dimFile = file.length();
                    
                    if(file.isDirectory()) {
                    	System.out.println("Copio solo primo livello, " + file.getName() + " directory non trasferita!");
                    	continue;
					} else if (dimFile > dimensioneSoglia) {
                        // Posso inviare la richesta
                    	System.out.println("Posso inviare al server il file " + file.getName() + " poichè supera soglia in dimensione.");
                    	
                        try {
                            socketDataOut.writeUTF(file.getName());
                        } catch (IOException e) {
                            // Non riesco a inviare la richiesta continuo alla prossima.
                            e.printStackTrace();
                            continue;
                        }
//-----------------------------------------------------------------------------------------------------------------------------
//qua si incorre nel problema se io faccio una richiesta qua e vengo successivamente deschedulato si potrebbe
//cascare nel problema che più clienti inizino a trasferire verso il server contemporameamente due file distinti
//con lo stesso identificativo!

//----------------------------------------------------------------------------------------------------------------------------

                        // Ricevo risposta
                        try {
                            risposta = socketDataIn.readUTF();
                        } catch (IOException e) {
                            // Errore lettura salto
                            e.printStackTrace();
                            continue;
                        }

                        // Decodifico la risposta
                        if (RESULT_ATTIVA.equalsIgnoreCase(risposta)) {
                        	System.out.println("File " + file.getName() + " non presente sul server, procedo ad inviarlo...");
                        	
                            // Invio lunghezza file
                            try {
                                socketDataOut.writeLong(dimFile);
                            } catch (IOException e) {
                                // Perché esco?
                                // Il server, non ricevendo la lunghezza del file, andrà in attesa fino a timeout
                                e.printStackTrace();
                                System.exit(IO_ERR);
                            }

                            // Posso inviare il file.
                            try (InputStreamReader inFile = new FileReader(file)) {
                                int tmpByte;
                                while ((tmpByte = inFile.read()) >= 0)
                                    socketDataOut.write(tmpByte);

                            } catch (IOException ex) {
                                // Perché esco?
                                // Il server, non ricevendo tutti i byte del file, andrà in attesa fino a timeout
                                ex.printStackTrace();
                                System.exit(IO_ERR);
                            }

                            // Ricevo ACK da server.ì
                            try {
                                risposta = socketDataIn.readUTF();
                            } catch (IOException e) {
                                // Errore lettura salto
                                e.printStackTrace();
                                continue;
                            }

                            // La nostra non è solo una scelta (furba tra l'altro), ma è proprio
                            // una specifica: "Il Multiple put viene effettuato file
                            // per file con assenso del server per ogni file" !!
                            if (RESULT_OK.equalsIgnoreCase(risposta)) {
                                System.out.println("File " + file.getName() + " caricato.");
                            } else {
                                System.out.println("Errore nell'invio " + file.getName() + ": " + risposta);
                            }

                        } else {
                        	System.out.println("File " + file.getName() + " già presente sul server, NON invio!");
                        }
                    } else { //altrimenti non supero la soglia lo comunico all'utente
                    	System.out.println("Non posso inviare al server il file " + file.getName() + " poichè sotto soglia! (" + dimensioneSoglia + ")");
                    }
                }
                System.out.println("Ho terminato di inviare tutti i file della cartella " + dirFile.getName());

                // Ho finito di inviare i file: chiudo la connessione.
                try {
                    socket.shutdownOutput();                           //Non invio più nulla.
                    System.out.println(socketDataIn.readUTF());        //Attendo una conferma chiusura.
                    socket.shutdownInput();                            //Chiudo l'input.
                    socket.close();                                    //Rilascio risorse.
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(IO_ERR);
                }
                
                System.out.println("Inserisci il nome della directory da trasferire sul server (EOF per terminare):");
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(IO_ERR);
        }

        //chiudo il canale di lettura da stdin
        try {
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
