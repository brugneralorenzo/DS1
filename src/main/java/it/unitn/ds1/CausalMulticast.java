package it.unitn.ds1;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import it.unitn.ds1.Chatter.JoinGroupMsg;
import it.unitn.ds1.Chatter.StartChatMsg;
import it.unitn.ds1.Chatter.PrintHistoryMsg;

public class CausalMulticast {
    final private static int N_LISTENERS = 10; // number of listening actors
    private static List<ActorRef> group = new ArrayList<>();
    final private static ActorSystem system = ActorSystem.create("helloakka");
    protected static FileOutputStream outputStream;
    protected static OutputStreamWriter outputStreamWriter;
    protected static BufferedWriter bufferedWriter;

    public static void addToGroup (ActorRef actorRef){
        group.add(actorRef);
    }
    public static void main(String[] args) throws InterruptedException, IOException {
        // Create the 'helloakka' actor system



        int id = 0;

        // the first four peers will be participating in conversations
        group.add(system.actorOf(Chatter.props(id), "Manager"));
        ActorRef a = system.actorOf(Chatter.props(-1), "Participants1");
        ActorRef b = system.actorOf(Chatter.props(-2), "Participants2");
        ActorRef c = system.actorOf(Chatter.props(-3), "Participants3");


        // send the group member list to everyone in the group
        JoinGroupMsg join = new JoinGroupMsg(0, new Chatter.Groups(0, new ArrayList<Integer>() {{
            add(0);
        }}, group));
        for (ActorRef peer : group) {
            peer.tell(join, null);
        }


        // tell the first chatter to start conversation

        group.get(0).tell(new Chatter.RequestJoin(), a);
        Thread.sleep(7000);
        group.get(0).tell(new Chatter.RequestJoin(), b);
        Thread.sleep(8000);
        group.get(1).tell(new  Chatter.Crash(), a);
//        Thread.sleep(8000);
//        group.get(0).tell(new Chatter.RequestJoin(), c);
//        Thread.sleep(5000);
//        group.get(2).tell(new  Chatter.Crash(), b);





        try {
            System.out.println(">>> Wait for the chats to stop and press ENTER <<<");
            System.in.read();

            outputStream = new FileOutputStream("output.txt");
            outputStreamWriter = new OutputStreamWriter(outputStream, "UTF-8");
            bufferedWriter = new BufferedWriter(outputStreamWriter);

            PrintHistoryMsg msg = new PrintHistoryMsg();
            for (ActorRef peer : group) {
                peer.tell(msg, null);
            }
            System.out.println(">>> Press ENTER to exit <<<");
            System.in.read();
        } catch (IOException ioe) {
        }
        bufferedWriter.close();
        system.terminate();
    }
}
