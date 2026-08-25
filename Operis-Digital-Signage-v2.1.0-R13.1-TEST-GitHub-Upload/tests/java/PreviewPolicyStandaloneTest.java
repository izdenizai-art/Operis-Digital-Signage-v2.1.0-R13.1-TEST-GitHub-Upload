import tr.izdeniz.signage.PreviewPolicy;

public final class PreviewPolicyStandaloneTest {
    private static void eq(int a,int b){if(a!=b)throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        eq(70,PreviewPolicy.clampQuality(70));
        eq(40,PreviewPolicy.clampQuality(10));
        eq(85,PreviewPolicy.clampQuality(99));
        eq(1280,PreviewPolicy.previewWidth(1920));
        eq(800,PreviewPolicy.previewWidth(800));
        System.out.println("PreviewPolicyStandaloneTest: PASS");
    }
}
