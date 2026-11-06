document.getElementById('grant').addEventListener('click', async () => {
  const status = document.getElementById('status');
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    stream.getTracks().forEach(track => track.stop());
    status.textContent = 'Đã cấp quyền và dừng microphone thử. Quay về tab cuộc họp rồi bắt đầu thu.';
  } catch { status.textContent = 'Chưa cấp được quyền. Kiểm tra quyền microphone của Chrome trong trình duyệt và cài đặt macOS.'; }
});
