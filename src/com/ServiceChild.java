package com;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

//Risassuntino per la presentazione:
//Finché non arriva EOF, il cliente mi invierà dei nomi file:
//1) Controllo sull'esistenza del file
//2) Lettura della dimensione.
//3) Scrivo il file che mi passa il cliente.
//4) Invio ACK
//Alla fine quando arriva EOF:
//1) Chiudo prima input(non ricevo più nulla)
//2) Chiudo l'output.

public class ServiceChild extends Thread {

	// Risposte da inviare al client
	private static final String RESULT_ATTIVA = "attiva";
	private static final String RESULT_SALTA_FILE = "salta file";
	private static final String RESULT_OK = "Comunicazione chiusa correttamente.";

	private Socket client = null;

	public ServiceChild(Socket client) {
		this.client = client;
	}

	// PROTOCOLLO
	// 1) ricevo il nome del file --> verifico se esiste nella directory corrente e
	// rispondo o RESULT_ATTIVA o RESULT_SALTA_FILE
	// 2) leggo la dimensione del file
	// 3) leggo gli n byte del file
	// 4) se tutto ok invia OK

	@Override
	public void run() {
		DataInputStream inSocket = null;
		DataOutputStream outSocket = null;

		String nomeCurrFile = "";

		try {
			inSocket = new DataInputStream(client.getInputStream());
			outSocket = new DataOutputStream(client.getOutputStream());
		} catch (IOException e) {
			System.err.println("Impossibile estrarre canali di comunicazione dalla socket!");
			// Non esco perchè ho una grossa perdita.
			// Meglio che fallisca il thread che l'intero processo.
			// System.exit(SOCK_ERR);
			return;
		}

		try {
			while ((nomeCurrFile = inSocket.readUTF()) != null) {
				// Tento di aprire il file nel monitor.

//--------------------------------------------------------------------------------------------------------------------------
//Qua anzichè avere la necessità di istanziare una classe che funga il ruolo di monitor potrei più semplicemente
//aggiungere un blocco di istruzioni syncronyzed che mi assicura che verranno eseguite insieme e nel caso in cui 
//si ricorra nel problema precedente sicuramente il primo client invia e finisce la sua richiesta e se non presente sul 
//fs del server, il server procede già a crearlo in modo tale che non appena arrivi il secondo cliente, trovi il file già 
//allocato if(Files.exists()) ritorna VERO!
//--------------------------------------------------------------------------------------------------------------------------
				File myFile = new File(nomeCurrFile);
				Path myPath = Path.of(myFile.toURI());
				FileWriter fileWriter = null;

				synchronized (this) {
					if (!Files.exists(myPath)) { // se il file non esiste il server richiede il trasf.
						// Devo per forza creare e aprire il file qua dentro.
						Files.createFile(myPath);
						fileWriter = new FileWriter(myFile);
					}
				}

				if (fileWriter != null) { // se il file non esiste il server richiede il trasf.
					outSocket.writeUTF(RESULT_ATTIVA);

					// da questo in poi il protocollo procede inviando dimensione prima e file dopo
					long dim = -1;
					dim = inSocket.readLong();

					// Scrivo il file (il file è già stato creato prima nel blocco eseguito in modo
					// mutuamente esclusivo)!
					try {
						for (int i = 0; i < dim; i++)
							fileWriter.write(inSocket.read());

					} catch (IOException e) {
						String err = "Errore nel creare il file: " + e.getMessage();
						System.err.println(err);
						// oltre a dire questo comunico anche al mio cliente che la trasmissione non è
						// andata a buon fine
						outSocket.writeUTF("FAILED");
						continue;
					}

					// la comunicazione è andata a buon fine! comunico al cliente!
					outSocket.writeUTF(RESULT_OK);

				} else { // altrimenti il file è gia presente nel fs --> salta file
					outSocket.writeUTF(RESULT_SALTA_FILE);
				}

			}
		} catch (EOFException e) { //Ricevuto EOF dal cliente che mi comunica --> tutta dir è stata inviata
			System.out.println("Ricevuto EOF! Il client ha terminato di caricare i file della directory.");
			try {
				//chiudo il canale di input (per il quale io sono schiavo) poichè mi hanno comunicato che non scrivono più
				client.shutdownInput();
				//Invio conferma chiusura che è attesa dal client
				outSocket.writeUTF(RESULT_OK);
				client.shutdownOutput(); //Chiudo l'output poichè non scrivo più nulla
				client.close(); //libero la socket occupata fino ad ora nella comuicazione
			} catch (IOException e1) {
				System.err.println("Errore nella chiusura della connessione.");
				e1.printStackTrace();
			}
			return;
		} catch (IOException e) {
			System.err.println("Errore nella lettura del nome del file!");
			e.printStackTrace();
			// Non esco perchè ho una grossa perdita.
			// Meglio che fallisca il thread che l'intero processo.
			// System.exit(NET_ERR);
		}
	}

}