import os

# 需要检查和清理的文件后缀
EXTS = ('.kts', '.kt', '.go', '.mod', '.toml', '.properties', '.pro', '.xml', '.mk', '.yml', '.yaml', '.js', '.md')

def clean_file(filepath):
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        
        # 移除文件开头所有由 --- 组成的虚线行
        while lines and lines[0].strip().startswith('---'):
            lines.pop(0)
        # 移除文件末尾所有由 --- 组成的虚线行
        while lines and lines[-1].strip().startswith('---'):
            lines.pop()

        with open(filepath, 'w', encoding='utf-8', newline='\n') as f:
            f.writelines(lines)
        print(f"✔ Cleaned: {filepath}")
    except Exception as e:
        pass

for root, dirs, files in os.walk('.'):
    if '.git' in dirs:
        dirs.remove('.git')
    for file in files:
        if file.endswith(EXTS) or file in ('gradlew', '.gitignore'):
            clean_file(os.path.join(root, file))

print("\n✨ 全项目虚线清理完毕！")