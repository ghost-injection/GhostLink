package net.ghostlink.mirror;

public final class RecoveryPolicyTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  RecoveryPolicy p=new RecoveryPolicy();long now=100000;
  check(p.due(now),"first recovery must be immediate");
  long[] delays={3000,6000,12000,24000,48000,60000,60000};
  for(long delay:delays){p.attempted(now);check(!p.due(now+delay-1),"must not retry early");check(p.due(now+delay),"retry deadline");check(p.remaining(now)==delay,"expected backoff");now+=delay;}
  p.reset();check(p.due(now),"network change restarts immediately");p.attempted(now);check(p.remaining(now)==3000,"network reset clears backoff");
  System.out.println("PASS: recovery backoff, cap, deadline and network reset");
 }
}
