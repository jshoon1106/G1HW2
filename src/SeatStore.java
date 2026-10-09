import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/** No I/O or queue locking inside a seat critical section. */
final class SeatStore {
    record Waiter(int client,int req,long registeredNs) {String key(){return client+":"+req;}}
    static final class Seat {
        final int number; final ReentrantLock lock=new ReentrantLock(); final ArrayDeque<Waiter> waiting=new ArrayDeque<>(); final Set<Integer> waitClients=new HashSet<>();
        int owner;long version,sequence,assigned,released;
        Seat(int number){this.number=number;}
    }
    record Snapshot(int seat,int owner,long version,long sequence,List<Waiter> waiters,long assigned,long released) {
        String waitText(){return waiters.isEmpty()?"-":String.join(",",waiters.stream().map(Waiter::key).toList());}
    }
    final Seat[] seats=new Seat[Shared.SEATS+1];
    final AtomicLong contention=new AtomicLong(),outstandingNotify;
    SeatStore(AtomicLong outstandingNotify){this.outstandingNotify=outstandingNotify;for(int n=1;n<=Shared.SEATS;n++)seats[n]=new Seat(n);}
    private Shared.State state(Seat s){return new Shared.State(s.number,s.version,s.owner);}
    private String audit(Seat s,Shared.Request r,String action,int before,Waiter w){
        return r.fields()+" seat="+s.number+" seq="+(++s.sequence)+" before="+before+" after="+s.owner+" version="+s.version+" action="+action+
            " waitClient="+(w==null?0:w.client())+" waitReq="+(w==null?0:w.req());
    }
    Shared.Reply apply(Shared.Request r)throws InterruptedException {
        int[] numbers=r.seats();String invalid=r.invalid();
        if(invalid==null) {
            if(r.op().equals("RESERVE_MULTI")) {if(numbers.length<2||numbers.length>4)invalid="COUNT";}
            else if(numbers.length!=1)invalid="COUNT";
            if(invalid==null&&Arrays.stream(numbers).anyMatch(n->n<1||n>Shared.SEATS))invalid="RANGE";
            if(invalid==null&&Arrays.stream(numbers).distinct().count()!=numbers.length)invalid="DUPLICATE_SEAT";
        }
        if(invalid!=null)return new Shared.Reply("FAIL",invalid,List.of(),List.of(),null);
        int[] sorted=numbers.clone();Arrays.sort(sorted);int acquired=0;
        List<String> audit=new ArrayList<>();List<Shared.State> states=new ArrayList<>();Shared.Notification notification=null;
        String result="FAIL",reason="OK";
        try {
            for(int n:sorted){Seat s=seats[n];if(!s.lock.tryLock()){contention.incrementAndGet();s.lock.lockInterruptibly();}acquired++;}
            if(r.op().equals("RESERVE_MULTI")) {
                boolean available=Arrays.stream(sorted).allMatch(n->seats[n].owner==0);
                if(available){for(int n:sorted){Seat s=seats[n];s.owner=r.client();s.version++;s.assigned++;audit.add(audit(s,r,"ASSIGN",0,null));}result="SUCCESS";}
                else reason="TAKEN";
            } else {
                Seat s=seats[numbers[0]];
                if(r.op().equals("RESERVE")) {
                    if(s.owner==0){s.owner=r.client();s.version++;s.assigned++;audit.add(audit(s,r,"ASSIGN",0,null));result="SUCCESS";}
                    else if(s.owner==r.client())reason="ALREADY_OWNER";
                    else if(s.waitClients.contains(r.client()))reason="ALREADY_WAITING";
                    else {Waiter w=new Waiter(r.client(),r.id(),System.nanoTime());s.waiting.addLast(w);s.waitClients.add(r.client());audit.add(audit(s,r,"WAITLIST",s.owner,w));result="WAITLISTED";reason="WAITLIST";}
                } else if(r.op().equals("CANCEL")) {
                    if(s.owner!=r.client())reason="NOT_OWNER";
                    else {
                        int before=s.owner;s.released++;Waiter w=s.waiting.pollFirst();
                        if(w==null){s.owner=0;s.version++;audit.add(audit(s,r,"RELEASE",before,null));}
                        else {s.waitClients.remove(w.client());s.owner=w.client();s.version++;s.assigned++;outstandingNotify.incrementAndGet();
                            audit.add(audit(s,r,"HANDOFF",before,w));notification=new Shared.Notification(w.client(),w.req(),s.number,s.version,w.registeredNs());}
                        result="SUCCESS";
                    }
                } else reason="FORMAT";
            }
            for(int n:sorted)states.add(state(seats[n]));
        } finally {for(int i=acquired-1;i>=0;i--)seats[sorted[i]].lock.unlock();}
        // LOCK is captured acquisition order; actual file output is outside all seat locks.
        audit.add(0,"LOCK "+r.fields()+" acquired="+Shared.csv(sorted));
        return new Shared.Reply(result,reason,List.copyOf(states),List.copyOf(audit),notification);
    }
    List<Snapshot> snapshot()throws InterruptedException {
        List<Snapshot> result=new ArrayList<>();for(int n=1;n<=Shared.SEATS;n++){Seat s=seats[n];s.lock.lockInterruptibly();try{
            result.add(new Snapshot(n,s.owner,s.version,s.sequence,List.copyOf(s.waiting),s.assigned,s.released));
        }finally{s.lock.unlock();}}return List.copyOf(result);
    }
}
