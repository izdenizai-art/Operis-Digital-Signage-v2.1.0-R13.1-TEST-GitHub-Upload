import tr.izdeniz.signage.CommandCapabilityPolicy;
public final class CommandCapabilityPolicyStandaloneTest {
  private static void yes(String c){if(!CommandCapabilityPolicy.supports(false,false,c))throw new AssertionError(c+" should be safe");}
  private static void no(String c){if(CommandCapabilityPolicy.supports(false,false,c))throw new AssertionError(c+" should not be normal-mode privileged");}
  public static void main(String[] args){
    for(String c:new String[]{"REFRESH","SCREEN_REFRESH","PROFILE_SYNC","TIME_REFRESH","TELEMETRY_REFRESH","CONNECTION_REFRESH","PLAYLIST_REFRESH","SCHEDULE_REFRESH","RETURN_TO_BASE_LAYOUT","SCREEN_PREVIEW_REFRESH"})yes(c);
    for(String c:new String[]{"DEVICE_REBOOT","KIOSK_LOCK","KIOSK_UNLOCK","POWER_ON"})no(c);
    if(!CommandCapabilityPolicy.supports(true,false,"DEVICE_REBOOT"))throw new AssertionError("owner reboot");
    System.out.println("CommandCapabilityPolicyStandaloneTest: PASS");
  }
}
