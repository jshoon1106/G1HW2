import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Offline verification: never sends network messages and never repairs client state. */
public final class LogVerifier {
    record Entry(String node,String event,String status,Map<String,String> f) {}
    record RequestRecord(String op,int[] seats) {}
    record ResponseRecord(String result,String reason,String states) {}
    record Notice(int seat,long version) {}
    record Audit(int client,int req,int seat,long seq,int before,int after,long version,String action,int waitClient,int waitReq) {}
    record FinalSeat(int owner,long version,long seq,String waiting,long assigned,long released) {}
    static final Pattern LINE=Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}] (\\S+) \\| (\\S+) \\| (\\S+) \\| (.*)$");
    @FunctionalInterface interface Handler {void accept(Entry e)throws Exception;}
    final Path dir;final String expectedProfile;
    int clients,count,minMs,maxMs;String run,hash;Map<String,String> init,summary;
    RequestRecord[][] received,sent;ResponseRecord[][] serverResponses,clientResponses;int[][][] locks;
    final Map<Long,Notice> serverNotices=new HashMap<>(),clientNotices=new HashMap<>(),handoffs=new HashMap<>();
    final Map<Long,List<Audit>> byRequest=new HashMap<>();final List<List<Audit>> audit=new ArrayList<>();
    final Map<Integer,FinalSeat> finalSeats=new HashMap<>();final Set<Integer> byes=new HashSet<>(),hello=new HashSet<>();
    final Map<Integer,Set<Integer>> clientHeld=new HashMap<>();final Map<Integer,Map<String,String>> clientFinal=new HashMap<>();
    long hot,seatReferences,responseNs,clientNotifyCount,waitlistedCount,assigned,released,serverWaitNs;int serverTerminated,pools,maxPoolQueue;
    LogVerifier(Path dir,String profile){this.dir=dir;expectedProfile=profile;for(int n=0;n<=100;n++)audit.add(new ArrayList<>());}
    static long key(int client,int req){return ((long)client<<32)|(req&0xffffffffL);}
    static void check(boolean good,String message)throws IOException {if(!good)throw new IOException(message);}
    static String field(Map<String,String> f,String name)throws IOException {String v=f.get(name);check(v!=null,"Missing field "+name);return v;}
    static int integer(Map<String,String> f,String name)throws IOException {return Integer.parseInt(field(f,name));}
    static long number(Map<String,String> f,String name)throws IOException {return Long.parseLong(field(f,name));}
    static void read(Path path,Handler handler)throws Exception {
        check(Files.isRegularFile(path),"Missing "+path.getFileName());
        try(BufferedReader in=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            int n=0;for(String text;(text=in.readLine())!=null;){n++;Matcher m=LINE.matcher(text);check(m.matches(),"Bad log line "+path.getFileName()+":"+n);handler.accept(new Entry(m.group(1),m.group(2),m.group(3),Shared.fields(m.group(4))));}
        }
    }
    void identity(Map<String,String> f)throws Exception {
        check(field(f,"run").equals(run)&&field(f,"profile").equals(expectedProfile)&&field(f,"sha256").equals(hash),"Run/profile/JAR mismatch");
        check(integer(f,"clients")==clients&&integer(f,"requests")==count&&integer(f,"minMs")==minMs&&integer(f,"maxMs")==maxMs,"Configuration mismatch");
        check(field(f,"timezone").equals("KST"),"Timezone mismatch");
    }
    void initialise(Map<String,String> f)throws Exception {
        check(init==null,"Duplicate server INIT");init=f;run=field(f,"run");hash=field(f,"sha256");
        clients=integer(f,"clients");count=integer(f,"requests");minMs=integer(f,"minMs");maxMs=integer(f,"maxMs");
        check(clients>=1&&clients<=30&&count>=1&&count<=5000,"Invalid workload size");
        check(field(f,"profile").equals(expectedProfile),"Wrong verification profile");
        check(integer(f,"workers")==10&&integer(f,"listener")==1&&integer(f,"notifier")==1&&integer(f,"monitor")==1&&integer(f,"seats")==100,"Wrong server architecture");
        check(integer(f,"queueLimit")==clients*count,"Wrong queue limit");
        check(field(f,"timezone").equals("KST"),"Server timezone missing");
        if(expectedProfile.equals("final"))check(clients==30&&count==5000&&minMs==200&&maxMs==1000&&field(f,"os").toLowerCase(Locale.ROOT).contains("linux"),"Final workload or remote Linux evidence missing");
        received=new RequestRecord[clients+1][count+1];sent=new RequestRecord[clients+1][count+1];
        serverResponses=new ResponseRecord[clients+1][count+1];clientResponses=new ResponseRecord[clients+1][count+1];locks=new int[clients+1][count+1][];
    }
    int[] requestIds(Map<String,String> f)throws Exception {int c=integer(f,"client"),r=integer(f,"req");check(c>=1&&c<=clients&&r>=1&&r<=count,"Request id outside workload");return new int[]{c,r};}
    RequestRecord request(Map<String,String> f)throws Exception {String op=field(f,"op");check(Set.of("RESERVE","RESERVE_MULTI","CANCEL").contains(op),"Unknown operation");return new RequestRecord(op,Shared.parseCsv(field(f,"seats")));}
    ResponseRecord response(Map<String,String> f)throws Exception {String result=field(f,"result");check(Set.of("SUCCESS","FAIL","WAITLISTED").contains(result),"Unknown result");return new ResponseRecord(result,field(f,"reason"),field(f,"states"));}
    void server(Entry e)throws Exception {
        if(e.node().equals("VERIFIER")){check(serverTerminated==1&&e.event().equals("VERIFY")&&field(e.f(),"phase").equals("post-run"),"Unexpected verifier footer");return;}
        check(e.node().equals("SERVER"),"Non-server event in Server.txt");Map<String,String> f=e.f();
        if(e.event().equals("INIT")){initialise(f);return;}check(init!=null,"Event before INIT");
        if(e.event().equals("TERMINATE")){check(e.status().equals("SUCCESS")&&field(f,"execution").equals("COMPLETE"),"Server terminated unsuccessfully");check(++serverTerminated==1,"Duplicate server termination");}
        if(e.event().equals("HEALTH")&&e.status().equals("FAIL"))throw new IOException("Server stalled/failed");
        if(e.event().equals("CONNECT")&&"HELLO".equals(f.get("phase"))) {int c=integer(f,"client");check(c>=1&&c<=clients&&hello.add(c),"Duplicate/invalid HELLO");}
        if(e.event().equals("PROTOCOL")&&"IN".equals(f.get("direction"))) {int[] id=requestIds(f);check(received[id[0]][id[1]]==null,"Duplicate server request");received[id[0]][id[1]]=request(f);}
        if(e.event().equals("PROTOCOL")&&"BYE".equals(f.get("type"))) {int c=integer(f,"client");check(c>=1&&c<=clients&&byes.add(c),"Duplicate/invalid BYE");}
        if(e.event().equals("RESPONSE")) {int[] id=requestIds(f);check(serverResponses[id[0]][id[1]]==null,"Duplicate server RESP");serverResponses[id[0]][id[1]]=response(f);}
        if(e.event().equals("LOCK")) {int[] id=requestIds(f);check(locks[id[0]][id[1]]==null,"Duplicate LOCK");locks[id[0]][id[1]]=Shared.parseCsv(field(f,"acquired"));}
        if(e.event().equals("AUDIT")) {
            int[] id=requestIds(f);int seat=integer(f,"seat");check(seat>=1&&seat<=100,"Bad AUDIT seat");
            Audit a=new Audit(id[0],id[1],seat,number(f,"seq"),integer(f,"before"),integer(f,"after"),number(f,"version"),field(f,"action"),integer(f,"waitClient"),integer(f,"waitReq"));
            audit.get(seat).add(a);byRequest.computeIfAbsent(key(id[0],id[1]),k->new ArrayList<>()).add(a);
        }
        if(e.event().equals("NOTIFY")) {int[] id=requestIds(f);check(serverNotices.putIfAbsent(key(id[0],id[1]),new Notice(integer(f,"seat"),number(f,"version")))==null,"Duplicate server NOTIFY");check(number(f,"waitNs")>=0,"Negative wait duration");serverWaitNs+=number(f,"waitNs");}
        if(e.event().equals("POOL")) {
            pools++;int q=integer(f,"queue");check(q>=0&&q<=clients*count,"Bad queue length");maxPoolQueue=Math.max(maxPoolQueue,q);Set<Integer> seen=new HashSet<>();
            for(String value:field(f,"seats").split(",")){String[] p=value.split(":");check(p.length==3,"Bad POOL seat");int seat=Integer.parseInt(p[0]),owner=Integer.parseInt(p[1]),waiting=Integer.parseInt(p[2]);check(seat>=1&&seat<=100&&seen.add(seat)&&owner>=0&&owner<=clients&&waiting>=0,"Bad POOL state");}check(seen.size()==100,"POOL omits seats");
        }
        if(e.event().equals("STAT")&&"FINAL_SEAT".equals(f.get("type"))) {
            int seat=integer(f,"seat");check(seat>=1&&seat<=100,"Bad final seat");check(finalSeats.putIfAbsent(seat,new FinalSeat(integer(f,"owner"),number(f,"version"),number(f,"seq"),field(f,"waiting"),number(f,"assigned"),number(f,"released")))==null,"Duplicate final seat");
        }
        if(e.event().equals("STAT")&&"SUMMARY".equals(f.get("type"))){check(summary==null,"Duplicate summary");summary=f;}
    }
    void client(int id)throws Exception {
        boolean[] initialised={false},bye={false},terminated={false};long[] previousSent={0},elapsedSum={0};
        read(dir.resolve("Client"+id+".txt"),e->{
            Map<String,String> f=e.f();
            if(e.node().equals("VERIFIER")){check(terminated[0]&&e.event().equals("VERIFY")&&field(f,"phase").equals("post-run"),"Unexpected client verifier footer");return;}
            check(e.node().equals("CLIENT"+id),"Wrong client file identity");
            if(e.event().equals("INIT")){check(!initialised[0],"Duplicate client INIT");identity(f);check(integer(f,"client")==id,"Wrong client INIT id");initialised[0]=true;return;}
            check(initialised[0],"Client event before INIT");
            if(e.event().equals("SEND")) {
                int[] ids=requestIds(f);check(ids[0]==id&&sent[id][ids[1]]==null,"Duplicate/misplaced SEND");RequestRecord r=request(f);sent[id][ids[1]]=r;
                int delay=integer(f,"delayMs");check(delay>=minMs&&delay<=maxMs,"Request interval configuration violation");long now=number(f,"sentNs");
                if(previousSent[0]!=0)check(now>=previousSent[0]&&number(f,"gapNs")==now-previousSent[0],"Nonmonotonic send clock");previousSent[0]=now;
                for(int seat:r.seats()){check(seat>=1&&seat<=100,"Generated seat out of range");seatReferences++;if(seat<=10)hot++;}
            }
            if(e.event().equals("RESPONSE")){int[] ids=requestIds(f);check(ids[0]==id&&clientResponses[id][ids[1]]==null,"Duplicate/misplaced client RESP");clientResponses[id][ids[1]]=response(f);long elapsed=number(f,"elapsedNs");check(elapsed>=0,"Negative response time");responseNs+=elapsed;elapsedSum[0]+=elapsed;}
            if(e.event().equals("NOTIFY")){int[] ids=requestIds(f);check(ids[0]==id&&clientNotices.putIfAbsent(key(id,ids[1]),new Notice(integer(f,"seat"),number(f,"version")))==null,"Duplicate/misplaced client NOTIFY");clientNotifyCount++;}
            if(e.event().equals("PROTOCOL")&&"BYE".equals(f.get("type"))){check(!bye[0],"Duplicate client BYE");bye[0]=true;}
            if(e.event().equals("STAT")&&"FINAL".equals(f.get("type"))) {
                check(bye[0]&&clientFinal.putIfAbsent(id,f)==null,"Missing BYE/duplicate FINAL log");check(integer(f,"sent")==count&&integer(f,"firstResponses")==count,"Incomplete client final counts");
                Set<Integer> held=new TreeSet<>();for(int n:Shared.parseCsv(field(f,"held")))check(n>=1&&n<=100&&held.add(n),"Bad/duplicate final held seat");clientHeld.put(id,held);
            }
            if(e.event().equals("TERMINATE")){check(e.status().equals("SUCCESS")&&field(f,"execution").equals("COMPLETE")&&!terminated[0],"Client unsuccessful termination");terminated[0]=true;}
        });
        check(initialised[0]&&bye[0]&&terminated[0]&&clientFinal.containsKey(id),"Missing client completion "+id);
        check(number(clientFinal.get(id),"responseNs")==elapsedSum[0],"Client response-time sum mismatch "+id);
    }
    void replaySeats()throws Exception {
        check(finalSeats.size()==100,"Missing final seats");Set<Long> unresolved=new HashSet<>();
        for(int seat=1;seat<=100;seat++) {
            List<Audit> events=audit.get(seat);events.sort(Comparator.comparingLong(Audit::seq));int owner=0;long seq=0,version=0,aCount=0,rCount=0;
            ArrayDeque<Long> waiting=new ArrayDeque<>();Set<Integer> waitClients=new HashSet<>();
            for(Audit a:events) {
                check(a.seq()==++seq&&a.before()==owner,"Seat sequence/owner transition mismatch seat="+seat);long requestKey=key(a.client(),a.req());
                RequestRecord request=received[a.client()][a.req()];check(request!=null&&Arrays.stream(request.seats()).anyMatch(n->n==a.seat()),"Audit not associated with request");
                switch(a.action()) {
                    case "ASSIGN" -> {check(owner==0&&a.after()==a.client()&&!request.op().equals("CANCEL"),"Double booking/invalid assignment seat="+seat);owner=a.after();version++;aCount++;}
                    case "WAITLIST" -> {
                        check(request.op().equals("RESERVE")&&owner!=0&&owner!=a.client()&&a.after()==owner&&a.waitClient()==a.client()&&a.waitReq()==a.req()&&waitClients.add(a.client()),"Invalid/duplicate waitlist");waiting.addLast(requestKey);
                    }
                    case "RELEASE" -> {check(request.op().equals("CANCEL")&&owner==a.client()&&a.after()==0&&waiting.isEmpty(),"Release bypassed waitlist/wrong owner");owner=0;version++;rCount++;}
                    case "HANDOFF" -> {
                        long target=key(a.waitClient(),a.waitReq());check(request.op().equals("CANCEL")&&owner==a.client()&&!waiting.isEmpty()&&waiting.removeFirst()==target&&a.after()==a.waitClient(),"FIFO handoff mismatch");
                        waitClients.remove(a.waitClient());owner=a.after();version++;aCount++;rCount++;check(handoffs.putIfAbsent(target,new Notice(seat,version))==null,"Duplicate handoff notification");
                    }
                    default -> throw new IOException("Unknown audit action");
                }
                check(owner>=0&&owner<=clients&&a.version()==version,"Owner version mismatch");
            }
            FinalSeat f=finalSeats.get(seat);String pending=waiting.isEmpty()?"-":String.join(",",waiting.stream().map(k->(k>>>32)+":"+(int)(long)k).toList());
            check(f.owner()==owner&&f.version()==version&&f.seq()==seq&&f.waiting().equals(pending)&&f.assigned()==aCount&&f.released()==rCount,"Final seat does not match replay seat="+seat);
            for(long k:waiting)check(unresolved.add(k),"Waitlist request duplicated across seats");assigned+=aCount;released+=rCount;
        }
        check(serverNotices.equals(handoffs)&&clientNotices.equals(serverNotices),"Generated/sent/received NOTIFY mismatch");
        Set<Long> waits=new HashSet<>();long successes=0,failures=0;
        for(int c=1;c<=clients;c++)for(int r=1;r<=count;r++) {
            RequestRecord a=received[c][r],b=sent[c][r];ResponseRecord sr=serverResponses[c][r],cr=clientResponses[c][r];
            check(a!=null&&b!=null&&sr!=null&&cr!=null,"Missing request/first response client="+c+" req="+r);
            check(a.op().equals(b.op())&&Arrays.equals(a.seats(),b.seats())&&sr.equals(cr),"Wire/log response mismatch client="+c+" req="+r);
            List<Audit> mutations=byRequest.getOrDefault(key(c,r),List.of());int[] actualLocks=locks[c][r];int[] sorted=a.seats().clone();Arrays.sort(sorted);
            check(actualLocks!=null&&Arrays.equals(actualLocks,sorted),"Lock ordering mismatch client="+c+" req="+r);
            if(sr.result().equals("FAIL")){failures++;check(mutations.isEmpty(),"Failed request changed seat/waitlist");}
            else if(sr.result().equals("WAITLISTED")) {waitlistedCount++;waits.add(key(c,r));check(a.op().equals("RESERVE")&&mutations.size()==1&&mutations.get(0).action().equals("WAITLIST"),"Bad WAITLISTED mutation");}
            else {
                successes++;if(a.op().equals("CANCEL"))check(mutations.size()==1&&Set.of("RELEASE","HANDOFF").contains(mutations.get(0).action()),"Bad CANCEL mutation");
                else {check(mutations.size()==a.seats().length,"Partial reservation/MULTI");Set<Integer> assignedSeats=new HashSet<>();for(Audit event:mutations)check(event.action().equals("ASSIGN")&&assignedSeats.add(event.seat()),"Bad reservation mutation");for(int seat:a.seats())check(assignedSeats.contains(seat),"MULTI missing seat");}
            }
            if(a.op().equals("RESERVE_MULTI"))check(a.seats().length>=2&&a.seats().length<=4&&Arrays.stream(a.seats()).distinct().count()==a.seats().length&&!sr.result().equals("WAITLISTED"),"Invalid generated MULTI");
        }
        Set<Long> accounted=new HashSet<>(handoffs.keySet());check(Collections.disjoint(accounted,unresolved),"Notified request unresolved");accounted.addAll(unresolved);check(waits.equals(accounted),"WAITLISTED balance mismatch");
        int occupied=0;for(int seat=1;seat<=100;seat++) {int owner=finalSeats.get(seat).owner(),heldBy=0;for(int c=1;c<=clients;c++)if(clientHeld.get(c).contains(seat)){check(heldBy==0,"Final duplicate client holding");heldBy=c;}check(owner==heldBy,"Final client/server owner mismatch seat="+seat);if(owner!=0)occupied++;}
        check(assigned-released==occupied,"Assignment/release balance mismatch");
        long pendingSum=0,notifySum=0,clientResponseNs=0;
        for(int c=1;c<=clients;c++){Map<String,String> f=clientFinal.get(c);long ownPending=0,ownNotify=0;for(long k:unresolved)if((k>>>32)==c)ownPending++;for(long k:clientNotices.keySet())if((k>>>32)==c)ownNotify++;
            check(number(f,"unresolved")==ownPending&&number(f,"notify")==ownNotify,"Client wait/notify summary mismatch");pendingSum+=ownPending;notifySum+=ownNotify;clientResponseNs+=number(f,"responseNs");}
        check(clientResponseNs==responseNs&&notifySum==clientNotifyCount&&waitlistedCount==notifySum+pendingSum,"Client response/wait totals mismatch");
        check(summary!=null&&number(summary,"firstResponses")==clients*(long)count&&number(summary,"notify")==clientNotifyCount&&number(summary,"unresolved")==pendingSum&&number(summary,"assigned")==assigned&&number(summary,"released")==released&&number(summary,"occupied")==occupied,"Server summary mismatch");
        check(number(summary,"deadlocks")==0&&number(summary,"maxQueue")>=maxPoolQueue&&number(summary,"maxQueue")<=clients*(long)count&&number(summary,"contention")>=0,"Bad server performance counters");
        long elapsed=number(summary,"elapsedNs");check(elapsed>0,"Invalid run duration");
        check(Math.abs(Double.parseDouble(field(summary,"throughput"))-clients*(double)count/(elapsed/1_000_000_000.0))<=0.000001,"Throughput mismatch");
        check(Math.abs(Double.parseDouble(field(summary,"meanWaitSec"))-(clientNotifyCount==0?0:serverWaitNs/1_000_000_000.0/clientNotifyCount))<=0.000001,"Waitlist average mismatch");
        check(seatReferences>0&&hot*2>=seatReferences,"Hot-seat concentration below 50 percent");
        check(hello.size()==clients&&byes.size()==clients&&serverTerminated==1,"Server connection/termination counts mismatch");
        if(number(summary,"elapsedNs")>=6_000_000_000L)check(pools>0,"Missing periodic POOL");
        summary=new LinkedHashMap<>(summary);summary.put("success",Long.toString(successes));summary.put("fail",Long.toString(failures));summary.put("waitlisted",Long.toString(waitlistedCount));
        summary.put("meanResponseMs",String.format(Locale.ROOT,"%.6f",responseNs/1_000_000.0/(clients*(long)count)));summary.put("hotRatio",String.format(Locale.ROOT,"%.6f",hot/(double)seatReferences));
    }
    String clientMetrics(int id)throws Exception {
        long success=0,fail=0,waitlisted=0,elapsed=0;
        for(int r=1;r<=count;r++){switch(clientResponses[id][r].result()){case "SUCCESS"->success++;case "FAIL"->fail++;case "WAITLISTED"->waitlisted++;}}
        Map<String,String> f=clientFinal.get(id);elapsed=number(f,"responseNs");
        String mean=String.format(Locale.ROOT,"%.6f",elapsed/1_000_000.0/count);
        if(f.containsKey("success"))check(number(f,"success")==success&&number(f,"fail")==fail&&number(f,"waitlisted")==waitlisted&&field(f,"meanResponseMs").equals(mean),"Client final metrics mismatch");
        return "client="+id+" sent="+count+" firstResponses="+count+" success="+success+" fail="+fail+" waitlisted="+waitlisted+" meanResponseMs="+mean+" responseNs="+elapsed+" notify="+field(f,"notify")+" unresolved="+field(f,"unresolved")+" held="+field(f,"held");
    }
    // Replace only the post-run suffix; leave every original runtime byte intact.
    void writeFooter(Path path,String footer)throws Exception {
        long position=0,lineStart=0,cut=-1;ByteArrayOutputStream line=new ByteArrayOutputStream();
        try(InputStream in=new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer=new byte[65536];
            outer:for(int n;(n=in.read(buffer))!=-1;)for(int i=0;i<n;i++) {
                byte b=buffer[i];position++;
                if(b=='\n') {
                    if(line.toString(StandardCharsets.UTF_8).contains(" VERIFIER | VERIFY | ")){cut=lineStart;break outer;}
                    line.reset();lineStart=position;
                } else line.write(b);
            }
        }
        Path replacement=Files.createTempFile(path.toAbsolutePath().getParent(),path.getFileName().toString()+"-", ".part");
        try {
            long remaining=cut>=0?cut:Files.size(path);
            try(InputStream in=Files.newInputStream(path);OutputStream out=Files.newOutputStream(replacement)) {
                byte[] buffer=new byte[65536];
                while(remaining>0){int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException("Log changed during verification");out.write(buffer,0,n);remaining-=n;}
                out.write(footer.getBytes(StandardCharsets.UTF_8));
            }
            try{Files.move(replacement,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(replacement,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(replacement);}
    }
    boolean verify()throws Exception {
        boolean pass=false;String error="-";
        Map<Integer,String> individual=new LinkedHashMap<>();
        try{read(dir.resolve("Server.txt"),this::server);check(init!=null,"Missing server INIT");for(int id=1;id<=clients;id++)client(id);replaySeats();for(int id=1;id<=clients;id++)individual.put(id,clientMetrics(id));pass=true;}
        catch(Exception e){error=Shared.clean(e.getClass().getSimpleName()+":"+e.getMessage());}
        StringBuilder report=new StringBuilder("result="+(pass?"PASS":"FAIL")+"\nprofile="+expectedProfile+"\nrun="+(run==null?"UNKNOWN":run)+"\nerror="+error+"\n");
        if(pass){report.append("doubleBooking=0\nconsistency=PASS\n");for(var field:summary.entrySet())report.append(field.getKey()).append('=').append(field.getValue()).append('\n');}
        String prefix="["+LocalTime.now(Shared.ZONE).format(Shared.CLOCK)+"] VERIFIER | VERIFY | "+(pass?"SUCCESS":"FAIL")+" | phase=post-run ";
        String global=prefix+"scope=global "+report.toString().strip().replace('\n',' ')+"\n";
        // Failed verification also replaces stale PASS metadata, but never claims partial metrics.
        Path server=dir.resolve("Server.txt");if(Files.isRegularFile(server))writeFooter(server,global);
        for(int id=1;id<=clients;id++){Path path=dir.resolve("Client"+id+".txt");if(Files.isRegularFile(path))writeFooter(path,(pass?prefix+"scope=client result=PASS run="+run+" "+individual.get(id)+"\n":"")+global);}
        System.out.println(report);return pass;
    }
    public static void main(String[] args) {
        int code;try {
            Map<String,String> options=new HashMap<>();for(int n=0;n<args.length;n+=2){if(n+1>=args.length||!Set.of("--log-dir","--profile").contains(args[n])||options.put(args[n],args[n+1])!=null)throw new IllegalArgumentException("Expected --log-dir DIR --profile final|dev");}
            if(!options.containsKey("--log-dir")||!Set.of("final","dev").contains(options.getOrDefault("--profile","final")))throw new IllegalArgumentException("Bad verification arguments");
            code=new LogVerifier(Path.of(options.get("--log-dir")),options.getOrDefault("--profile","final")).verify()?0:1;
        }catch(IllegalArgumentException e){System.err.println(e.getMessage());code=2;}catch(Exception e){e.printStackTrace();code=1;}if(code!=0)System.exit(code);
    }
}
