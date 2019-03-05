package ch.epfl.biop.wrappers.ilastik;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Supplier;

public class IlastikTask implements Runnable {

    /*
     //example:
     //ilastik.exe --headless --export_source="Simple Segmentation" --project=Z:\public\SandrineIsaac\WorkFlow_v1\MyProject-PixelClassification.ilp Z:\public\SandrineIsaac\Data\test_analysis_181210\exported_BF\20181102_SI41_LB_t5h_500ms_AEROBIOSIS.tif Z:\public\SandrineIsaac\Data\test_analysis_181210\exported_BF\20181102_SI41_LB_t5h_500ms_AEROBIOSIS_pic2.tif
     // Headless documentation for mac : https://www.ilastik.org/documentation/basics/headless.html
     $ ./run_ilastik.sh --headless \
                   --project=MyProject.ilp \
                   --output_format=tiff \
                   --output_filename_format=/tmp/results/{nickname}_results.tiff \
                   --output_filename_format
                   my_next_image1.png my_next_image2.png
     */

    private Supplier<String> ilastikProjectPathSupplier;

    private ArrayList<Supplier<String>> imageInPathSuppliers;

    public String export_source;

    public String export_dtype;

    public String output_filename_format;

    public IlastikTask(IlastixTaskBuilder builder) {
        this.imageInPathSuppliers=builder.imageInPathSuppliers;
        this.export_source=builder.export_source;
        this.export_dtype=builder.export_dtype;

        this.ilastikProjectPathSupplier=builder.ilastikProjectPathSupplier;
        this.output_filename_format = builder.output_filename_format;
    }

    public void run() {
        ArrayList<String> options = new ArrayList<>();
        options.add("--headless");
        System.out.println("this.export_source="+this.export_source);
        options.add("--project="+this.ilastikProjectPathSupplier.get());
        System.out.println(this.export_source);
        options.add("--export_source="+this.export_source);
        options.add("--export_dtype="+this.export_dtype);
        if (this.output_filename_format!="") {
            options.add("--output_filename_format=" + this.output_filename_format);
        }
        for (Supplier<String> ss:this.imageInPathSuppliers) {
            options.add(ss.get());
        }
        System.out.println(options);
        try {
            Ilastik.execute(options);
        } catch (IOException |InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static class IlastixTaskBuilder {

        private Supplier<String> ilastikProjectPathSupplier;

        private String export_source="Simple Segmentation";

        private String export_dtype="uint8";

        public String output_filename_format="{dataset_dir}/{nickname}_results.tiff";

        int nThreads=-1;

        private ArrayList<Supplier<String>> imageInPathSuppliers;

        public IlastixTaskBuilder() {
            imageInPathSuppliers = new ArrayList<>();
            nThreads=-1;
        }

        public IlastikTask.IlastixTaskBuilder image(Supplier<String> imgSupplier) {
            imageInPathSuppliers.add(imgSupplier);
            return this;
        }

        public IlastikTask.IlastixTaskBuilder project(Supplier<String> projectPathSupplier) {
            this.ilastikProjectPathSupplier = projectPathSupplier;
            return this;
        }

        public IlastikTask.IlastixTaskBuilder export_source(String str_arg) {
            this.export_source=str_arg;
            return this;
        }

        public IlastikTask.IlastixTaskBuilder export_dtype(String str_arg) {
            this.export_dtype=str_arg;
            return this;
        }

        public IlastikTask.IlastixTaskBuilder output_filename_format(String str_arg) {
            this.output_filename_format=str_arg;
            return this;
        }

        public IlastikTask build() {
            return new IlastikTask(this);
        }

    }

}
