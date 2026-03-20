import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.TaskException;

public class DemoThreadDeath {

    static String image_path = "";

    public static void main(String... args) throws Exception {

        image_path = "D:/code/ijl-utilities-wrappers/src/test/resources/blobs.tif";

        final Environment env = Appose
                .pixi()
                .channels("conda-forge")
                .conda("appose", "python==3.11", "numpy")
                .pypi("itk-elastix")
                .name("itk-elastix-v0")
                .logDebug()
                .build();

        Service service = env.python();

        for (int i = 0; i<80; i++) {
            final int fi = i;
            new Thread(() -> {
                try {
                    runTask(service, fi);
                    System.out.println("Task "+fi+" done.");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    public static void runTask(Service service, int i) throws TaskException, InterruptedException {
        final Service.Task task = service.task(
                "import itk\n"+
                      "image = itk.imread('"+image_path+"', itk.F)\n"+
                      "image = itk.imread('"+image_path+"', itk.F)\n"+
                      "image = itk.imread('"+image_path+"', itk.F)\n");
        task.listen(evt -> {
            System.out.println("[itk-elastix] #"+i+" " +evt);
            if (evt.message!=null) {
                System.out.println("[itk-elastix] #"+i+" " + evt.message);
            }

        });
        task.start();
        task.waitFor();
    }
}
