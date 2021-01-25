package ch.epfl.biop.wrappers.elastix;

import ij.ImagePlus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ch.epfl.biop.java.utilities.Converter;
import ch.epfl.biop.java.utilities.ConvertibleObject;
import ch.epfl.biop.java.utilities.TempDirectory;
import ch.epfl.biop.java.utilities.image.ConvertibleImage;

/**
 * Handles Elastix outputs file or chain of files in the case of transformation compositions
 * Transformations can be stored and retrieves from zip file which contains all the consecutive transformations
 */

public class RegisterHelper extends ConvertibleObject {

    ConvertibleImage fixedImage;
    ConvertibleImage movingImage;
    ArrayList<Supplier<String>> transformFilesSupplier;
    public Supplier<String> outputDir;

    public String initialTransformFilePath = null;

    ElastixTask align;

    boolean alignTaskSet = false;

    public RegisterHelper() {
        transformFilesSupplier = new ArrayList<>();
        fixedImage = new ConvertibleImage();
        movingImage = new ConvertibleImage();
    }

    public void setMovingImage(String pathToMovingImage) {
        movingImage.set(new File(pathToMovingImage));
        alignTaskSet = false;
    }

    public void addInitialTransformFromFilePath(String filePath) {
        initialTransformFilePath = filePath;
    }
    
    public void setMovingImage(ConvertibleImage img) {
    	movingImage=img;
    }
    
    public void setFixedImage(ConvertibleImage img) {
    	fixedImage=img;
    }

    public void setMovingImage(URL url) {
        movingImage.set(url);
        alignTaskSet = false;
    }

    public void setMovingImage(ImagePlus imp) {
        movingImage.set(imp);
        alignTaskSet = false;
    }

    public void setFixedImage(String pathToFixedImage) {
        fixedImage.set(new File(pathToFixedImage));
        alignTaskSet = false;
    }

    public void setOutputDirectory(Supplier<String> outputDir) {
        this.outputDir = outputDir;
    }
    
    static public String getFileFromRegistrationParameters(RegistrationParameters rp) {
    	return ((File)rp.to(File.class)).getAbsolutePath();
    }
    
    public void addTransform(RegistrationParameters rp) {
    	transformFilesSupplier.add(() -> RegisterHelper.getFileFromRegistrationParameters(rp));
    }

    public void addTransformFromFilePath(String pathToFile) {
        transformFilesSupplier.add(() -> pathToFile);
        alignTaskSet = false;
    }

    public void setFixedImage(ImagePlus imp) {
        fixedImage.set(imp);
        System.out.println("Fixed image fixé");
        alignTaskSet = false;
    }

    public void setFixedImage(URL url) {
        fixedImage.set(url);
        alignTaskSet = false;
    }

    /*public void setFixedImage(URL url, String extension) {
        fixedImage = new HDDBackedFile(url, extension);
        alignTaskSet = false;
    }*/

    public void setDefaultOutputDir() {
        TempDirectory tempDir = new TempDirectory("reg-out");
        tempDir.deleteOnExit();
        Path path = tempDir.getPath();
        outputDir = path::toString;
    }

    public boolean checkParametersForAlignement() {
        if (fixedImage.to(File.class)==null) {
            System.err.println("Fixed image not set");
            return false;
        }
        if (movingImage.to(File.class)==null) {
            System.err.println("Moving image not set");
            System.out.println("null ?"+(movingImage==null));

            System.out.println("null img ?"+(movingImage.to(ImagePlus.class)==null));
            System.out.println("null file ?"+(movingImage.to(File.class)==null));
            
            return false;
        } 
        if (transformFilesSupplier.size()==0) {
            System.err.println("No transformation specified");
            return false;
        }
        if (outputDir==null) {
            setDefaultOutputDir();
            if (outputDir == null) {
                System.err.println("Could not create output directory");
                return false;
            }
        }
        return true;
    }

    public String fixedImagePathSupplier() {
    	return ((File) fixedImage.to(File.class)).getAbsolutePath();
    }
    
    public String movingImagePathSupplier() {
    	return ((File) movingImage.to(File.class)).getAbsolutePath();
    }
    
    public void align() {
        if (!alignTaskSet) {
            if (checkParametersForAlignement()) {
                ElastixTaskSettings settings = new ElastixTaskSettings().fixedImage(this::fixedImagePathSupplier)
                        .movingImage(this::movingImagePathSupplier).outFolder(outputDir);

                for (Supplier<String> s : this.transformFilesSupplier) {
                    settings.addTransform(s);
                }

                if (this.initialTransformFilePath!=null) {
                    settings.addInitialTransform(initialTransformFilePath);
                }

                align = new ElastixTask(settings);
                alignTaskSet = true;
            } else {
                align = null;
            }
        }
        if (alignTaskSet) {
            try {
                align.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    public String getFinalTransformFile() {
        //File.pathSeparator
        return getTransformFile(transformFilesSupplier.size()-1);//outputDir.get()+File.separator+"TransformParameters."+()+".txt";
    }

    public String getTransformFile(int index) {
        //File.pathSeparator
        return outputDir.get()+File.separator+"TransformParameters."+(index)+".txt";
    }

    public int getNumberOfTransform() {
        return transformFilesSupplier.size();
    }
    
    @Converter
    public RHZipFile saveToZip(RegisterHelper rh) {
        try {
            String sourceFile = rh.outputDir.get();
            File temp = File.createTempFile("regtra", ".zip");
            temp.deleteOnExit();
            FileOutputStream fos = new FileOutputStream(temp);//temp.getName());
            ZipOutputStream zipOut = new ZipOutputStream(fos);
            File fileToZip = new File(sourceFile);
            zipFolderNR(fileToZip, zipOut);
            for (int i = 0; i < rh.transformFilesSupplier.size(); i++) {
                zipFile(new File(rh.transformFilesSupplier.get(i).get()),"RegisterParameters."+i+".txt",zipOut);
            }
            zipOut.close();
            fos.close();
            System.out.println(temp.getAbsolutePath());
            return new RHZipFile(temp);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void zipFolder(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith(File.separator)) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + File.separator));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                zipFolder(childFile,  fileName + File.separator +childFile.getName(), zipOut);
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }

    private static void zipFolderNR(File fileToZip, ZipOutputStream zipOut) throws IOException { // Non recursive
        //if (fileToZip.isHidden()) {
        //    return;
        //}
        if (fileToZip.isDirectory()) {
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                if (childFile.getName().startsWith("TransformParameters")) {
                    //zipFolderNR(childFile,  fileName + "/" +childFile.getName(), zipOut);
                    FileInputStream fis = new FileInputStream(childFile);

                    ZipEntry zipEntry = new ZipEntry(childFile.getName());
                    zipOut.putNextEntry(zipEntry);
                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = fis.read(bytes)) >= 0) {
                        zipOut.write(bytes, 0, length);
                    }
                    fis.close();
                }
            }
            return;
        }
    }

    private static void zipFile(File fileToZip, String name, ZipOutputStream zipOut) throws IOException { // Non recursive
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(name);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }

    /*
        public static void main(String[] args) throws IOException {
        String fileZip = "compressed.zip";
        byte[] buffer = new byte[1024];
        ZipInputStream zis = new ZipInputStream(new FileInputStream(fileZip));
        ZipEntry zipEntry = zis.getNextEntry();
        while(zipEntry != null){
            String fileName = zipEntry.getName();
            File newFile = new File("unzipTest/" + fileName);
            FileOutputStream fos = new FileOutputStream(newFile);
            int len;
            while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            fos.close();
            zipEntry = zis.getNextEntry();
        }
        zis.closeEntry();
        zis.close();
        }

     */

    //-------------------------------- TODO!!!!!

    @Converter
    public RegisterHelper loadFromZip(RHZipFile rzf) {
        // Initializes register helper
        RegisterHelper rh = new RegisterHelper();
        rh.setDefaultOutputDir();
        String pathOutputDir = rh.outputDir.get();

        //TempDirectory tempDir = new TempDirectory("reg-");
        //tempDir.deleteOnExit();
        //Path pathTransformations = pathOutputDir;//tempDir.getPath();
        Map<Integer, File> registerFiles = new HashMap<>();
        Map<Integer, File> transformFiles = new HashMap<>();
        
        try {
            // Unzips files into temp directory
            String fileZip = rzf.f.getAbsolutePath();
            byte[] buffer = new byte[1024];
            ZipInputStream zis = null;
            zis = new ZipInputStream(new FileInputStream(fileZip));
            ZipEntry zipEntry = zis.getNextEntry();
            while(zipEntry != null){
                String fileName = zipEntry.getName();
                File newFile;
                String regexTrParam  = "(TransformParameters\\.)(\\d+)(\\.txt)";
                String regexReParam  = "(RegisterParameters\\.)(\\d+)(\\.txt)";
                if (fileName.startsWith("TransformParameters")) {
                    String number  = fileName.replaceAll(regexTrParam, "$2");
                    //System.out.println("number="+number);
                	newFile = new File(pathOutputDir+File.separator+fileName);
                    newFile.deleteOnExit();
                	transformFiles.put(Integer.valueOf(number), newFile);
                    System.out.println(newFile.getAbsolutePath());
                } else {
                    assert fileName.startsWith("RegisterParameters");
                    String number  = fileName.replaceAll(regexReParam, "$2");
                    newFile = File.createTempFile("rpa", ".txt");
                    newFile.deleteOnExit();
                	registerFiles.put(Integer.valueOf(number), newFile);
                    System.out.println(newFile.getAbsolutePath());
                    //newFile = new File("unzipTest/" + fileName);
                }
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            zis.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Registration parameters can be put in the register helper element
        int i=0;
        while (registerFiles.containsKey(i)) {
        	rh.addTransformFromFilePath(registerFiles.get(i).getAbsolutePath());
        	i=i+1;
        }
        		
        // Transform downloaded files should be changed get the right path for transformation chaining
        i=1; // File 0 has no reference to previous file -> doesn't need to be changed
    	try {
    		String regexTr = "(\\(InitialTransformParametersFileName\\s\")(.+)(\"\\))";
	        while (transformFiles.containsKey(i)) {
	        	String verify, putData;
	        	File f = transformFiles.get(i);
	        	
	        	// input the file content to the StringBuffer "input"
	            BufferedReader file = new BufferedReader(new FileReader(f));
	            String line;
	            StringBuffer inputBuffer = new StringBuffer();
                System.out.println(transformFiles.get(i-1).getAbsolutePath().replaceAll("\\\\","\\\\\\\\"));
	            while ((line = file.readLine()) != null) {

	            	putData=line.replaceAll(regexTr, "$1"+transformFiles.get(i-1).getAbsolutePath().replaceAll("\\\\","\\\\\\\\")+"$3");
	                inputBuffer.append(putData);//line);
	                inputBuffer.append('\n');
	            }
	            String inputStr = inputBuffer.toString();
	            file.close();
	            FileOutputStream fileOut = new FileOutputStream(f);
	            fileOut.write(inputStr.getBytes());
	            fileOut.close();
				i=i+1;
				System.out.println(f.getAbsolutePath());
	        }
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        return rh;
    }





}