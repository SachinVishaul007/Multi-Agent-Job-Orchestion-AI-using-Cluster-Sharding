#!/bin/bash

# Create test Excel file
echo "Creating test Excel file..."
cat > test_resume.csv << 'EOF'
Resume Points,Detected Tags
"Developed RESTful APIs using Spring Boot and Java","java,spring boot,rest api"
"Implemented microservices architecture with Docker","docker,microservices,architecture"
"Built machine learning models using Python and TensorFlow","python,tensorflow,machine learning"
EOF

# Convert CSV to Excel using a simple Python script
python3 -c "
import pandas as pd
df = pd.read_csv('test_resume.csv')
df.to_excel('test_resume.xlsx', index=False)
print('Created test_resume.xlsx')
"

# Create test PDF file for base resume
echo "Creating test PDF file..."
echo "John Doe Resume Content - Software Engineer with 5 years experience" > test_base_resume.txt

echo "Test files created:"
ls -la test_resume.xlsx test_base_resume.txt 2>/dev/null || echo "Files not found"