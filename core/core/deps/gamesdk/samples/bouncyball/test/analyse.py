
#  Analyse output from logcat output of bouncyball
# adb logcat > out.log
#  grepped with
# grep FrameStat out.log > logcat_step.txt
import re
import matplotlib.pyplot as plt

total_line = re.compile(r'.*total frames:\s+(\d+)')
latency_line = re.compile(
    r'.*frame latency:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)')
offset_line = re.compile(
    r'.*offset from previous frame:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)')
def extract(regex):
    vals = []
    deltas = []
    totals = []
    total_delta = []
    with open('logcat_step.txt') as f:
        for line in f:
            match = re.match(regex, line.strip())
            if match:
                vals.append([int(match.group(1)), int(match.group(2)),
                             int(match.group(3)), int(match.group(4)),
                             int(match.group(5))])
            total_match = re.match(total_line, line.strip())
            if total_match:
                totals.append(int(total_match.group(1)))

    x = vals[0]
    cur = [x[0], x[1], x[2], x[3], x[4]]
    for x in vals:
        d = [x[0]-cur[0], x[1]-cur[1], x[2]-cur[2], x[3]-cur[3], x[4]-cur[4]]
        deltas.append(d)
        cur = x

    cur = totals[0]
    for x in totals:
        d = x-cur
        total_delta.append(d)
        cur = x
    return total_delta, deltas

latency_deltas = extract(latency_line)
offset_deltas = extract(offset_line)

print("Latency deltas")
print(latency_deltas)
print("Offset deltas")
print(offset_deltas)

plt.plot(latency_deltas[0])
plt.ylabel('Total n frames per second')
plt.xlabel('Time / s')
plt.savefig('TotalFramesPerSecond.png')
plt.show()

flipped_offsets = list(map(list, zip(*offset_deltas[1])))

colors = ['tab:blue', 'tab:red', 'tab:green', 'tab:purple']

for i in range(4):
    plt.plot(flipped_offsets[i+1], color=colors[i], label=str(i+1))

plt.ylabel('Frame count per second')
plt.legend(loc='upper right', title='Frame offset')
plt.xlabel('Time / s')
plt.savefig('FrameOffsets.png')
plt.show()
