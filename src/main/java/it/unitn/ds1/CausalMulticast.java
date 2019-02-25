package it.unitn.ds1;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.io.IOException;

import it.unitn.ds1.Chatter.JoinGroupMsg;
import it.unitn.ds1.Chatter.StartChatMsg;
import it.unitn.ds1.Chatter.PrintHistoryMsg;

public class CausalMulticast {
    final private static int N_LISTENERS = 10; // number of listening actors
    private static List<ActorRef> group = new ArrayList<>();
    final private static ActorSystem system = ActorSystem.create("helloakka");

    public static void addToGroup (ActorRef actorRef){
        group.add(actorRef);
    }
    public static void main(String[] args) throws InterruptedException {
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
        Thread.sleep(7000);
        group.get(0).tell(new Chatter.RequestJoin(), c);



        try {
            System.out.println(">>> Wait for the chats to stop and press ENTER <<<");
            System.in.read();

            PrintHistoryMsg msg = new PrintHistoryMsg();
            for (ActorRef peer : group) {
                peer.tell(msg, null);
            }
            System.out.println(">>> Press ENTER to exit <<<");
            System.in.read();
        } catch (IOException ioe) {
        }
        system.terminate();
    }
}
