import subprocess,pathlib
sql=pathlib.Path(r"D:\release\clipfetch\backend\src\main\resources\db\migration\mysql\V19__native_worker_instance_lease.sql").read_text(encoding="utf-8")
r=subprocess.run(["docker","exec","clipfetch-mysql-atomic-test","mysql","-uroot","-plocaltest123","clipfetch_jvm_it","-e",sql],text=True,capture_output=True)
print("exit",r.returncode,r.stdout,r.stderr)
