package net.ghostlink.mirror;

/** Monotonic, bounded retries; network changes may restart the retry budget. */
final class RecoveryPolicy {
 private long next;
 private int attempts;
 void reset(){next=0;attempts=0;}
 boolean due(long now){return now>=next;}
 void attempted(long now){next=now+Math.min(60000L,3000L*(1L<<Math.min(attempts++,5)));}
 long remaining(long now){return Math.max(0,next-now);}
}
