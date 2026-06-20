import pathlib  
p=pathlib.Path('Y:/AppData/Android_Studio/projects/ECHO+/app/src/main/java/com/example/echo/MainActivity.kt')  
b=p.read_bytes()  
m=b'\x24mid'  
i=b.find(m)  
while i 
    s=b.rfind(b'{',0,i)  
    e=b.find(b'}',i)  
    b=b[:s]+b[e+1:]  
    i=b.find(m)  
p.write_bytes(b)  
print(len(b))  
