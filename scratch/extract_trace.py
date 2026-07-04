def extract_trace():
    log_path = '/home/vanilla/Desktop/AntiGravity/MusicRe/logs.txt'
    with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
        lines = f.readlines()
        
    start = 214950
    end = 215010
    for j in range(start, end):
        if j < len(lines):
            print(f"{j}: {lines[j].strip()}")

if __name__ == '__main__':
    extract_trace()
