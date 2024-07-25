conda_env_path = "D:/conda/conda-envs/cellpose-307-gpu"
//run("Blobs (25K)"); // uncomment to test
image_title = getTitle();

run("Cellpose ..." ,"imp="+image_title+" conda_env_path="+conda_env_path+" diameter=30 model=cyto3 model_path= ch1=1 ch2=1 additional_flags=--use_gpu" );