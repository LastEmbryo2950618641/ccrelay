import sys
text = sys.stdin.read()
text = text.strip()
print('REMOTE_MOCK_RESPONSE')
print('PROMPT_CHARS=' + str(len(text)))
if text:
    print(text[:4000])
