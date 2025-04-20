# Pison Project in Java

This repository contains a Java implementation of the Pison, a scalable structural index construction for JSON analytics. The project includes classes for loading, processing, and querying JSON records. The code is organized into packages for easy management.

## Prerequisites

- **Java Development Kit (JDK):**  
  Make sure you have the JDK installed. You can download it from [Oracle](https://www.oracle.com/java/technologies/javase-downloads.html) or [AdoptOpenJDK](https://adoptopenjdk.net/).  
  Verify your installation by running:
  ```bash
  java -version
  javac -version
  ```

## How to Compile and Run

1. **Compile the Project:**  
   Open a terminal or command prompt at the project root and run:
   ```bash
   g++ -fPIC -mavx2 -mpclmul -msse2 -shared     -I${JAVA_HOME}/include -I${JAVA_HOME}/include/linux     -o libjsonsimd.so    include/bitmap_JsonSimd.cpp
   javac   -h include   -d bin   src/bitmap/*.java   src/records/*.java   src/tests/*.java   src/tokenizer/*.java   src/*.java
   ```
   This command compiles all the Java source files and places the resulting `.class` files in the `bin` directory.

2. **Run the Main Class:**  
   Since `Main.java` is in the default package, run:
   - For **Serial** records:
    ```bash
    java -Djava.library.path=. -cp bin Main dataset/twitter_sample_large_record.json --threads=1 --levels=3
    ```
    or
    ```bash
    java -Djava.library.path=. -cp bin Main --file=dataset/twitter_sample_large_record.json --threads=1 --levels=3
    ```
   - For **Parallel** records:
    ```bash
    java -Djava.library.path=. -cp bin Main dataset/twitter_sample_large_record.json --threads=16 --levels=3
    ```
    or
    ```bash
    java -Djava.library.path=. -cp bin Main --file=dataset/twitter_sample_large_record.json --threads=16 --levels=3
    ```
   This command runs your project using the compiled classes in the `bin` folder.

```bash
java -Djava.library.path=. -cp bin Main dataset/twitter_sample_large_record.json --threads=16 --levels=3
```
## Notes for Beginners

- **Understanding the Folder Structure:**  
  The source files are kept in the `src` folder, and the compiled classes go into the `bin` folder.  
  **Tip:** Make sure the `bin` folder is added to your `.gitignore` so that compiled files are not committed to GitHub.

- **Working with Java:**  
  When you modify your code, recompile using the provided `javac` command and run the project again using the `java` command.

- **Troubleshooting:**  
  If you encounter file path or permission issues, verify the file paths in your code and ensure you have the correct access rights.